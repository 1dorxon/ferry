package com.dorxon.browserdrop.proto

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * The wire format shared with the web client (public/app.js). Change nothing
 * here without changing it there: an iPhone or a PC on the other end runs that
 * code, not this.
 *
 * One ordered data channel per pair of devices, labelled "bd". Text messages are
 * JSON control messages keyed by "t":
 *
 *   sender   -> {t:"file", id, name, size, type}      announce, then wait
 *   receiver -> {t:"accept", id} | {t:"reject", id}   the user's answer
 *   sender   -> binary frames                          8-byte float64 offset + bytes
 *   sender   -> {t:"sent", id, size, chunk}            "that was everything"
 *   receiver -> {t:"got", id} | {t:"need", id, offsets} confirmation
 *   either   -> {t:"cancel"}                           stop whatever is in flight
 *
 * Text (the clipboard) needs no Accept and no binary frames:
 *
 *   sender   -> {t:"text", id, part, parts, text}     one message per part
 *   receiver -> {t:"textgot", id}                      once every part is in
 *
 * An older Ferry ignores "text", so a sender that hears nothing says so.
 * Offsets are big-endian float64 (a JS DataView default) and are always
 * multiples of `chunk`, the body size the sender used.
 */
object Proto {
    const val LABEL = "bd"
    const val PREFERRED_CHUNK = 256 * 1024
    const val HIGH_WATER = 8L * 1024 * 1024
    const val LOW_WATER = 2L * 1024 * 1024
    const val FRAME_HEADER = 8
    const val VERDICT_TIMEOUT_MS = 120_000L
    const val REPLY_TIMEOUT_MS = 30_000L
    const val CONNECT_DEADLINE_MS = 15_000L
    /** Characters per text part: 48 KB at worst in UTF-8, far below any SCTP limit. */
    const val TEXT_PART = 8192
    const val TEXT_MAX = 1_000_000
    const val TEXT_ACK_MS = 20_000L

    /** Split text for the wire, never through a surrogate pair (half an emoji). */
    fun textParts(s: String): List<String> {
        if (s.isEmpty()) return listOf("")
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            var j = min(s.length, i + TEXT_PART)
            if (j < s.length && Character.isLowSurrogate(s[j])) j--
            out += s.substring(i, j)
            i = j
        }
        return out
    }

    /** Same rule as chunkSizeFor() in the web client. */
    fun chunkSizeFor(maxMessageSize: Int?): Int =
        max(16 * 1024, min(PREFERRED_CHUNK, (maxMessageSize ?: 65536) - 1024))

    /**
     * The peer's SCTP message limit, from its SDP. Absent means the RFC 8841
     * default of 64 KiB; "0" means unlimited, which we treat as our own ceiling.
     */
    fun maxMessageSizeFromSdp(sdp: String?): Int? {
        val m = Regex("""a=max-message-size:(\d+)""").find(sdp ?: return null) ?: return 65536
        val v = m.groupValues[1].toLongOrNull() ?: return 65536
        return if (v == 0L) PREFERRED_CHUNK + 1024 else min(v, Int.MAX_VALUE.toLong()).toInt()
    }

    fun frame(offset: Long, body: ByteArray, len: Int): ByteBuffer {
        // Heap, not direct: WebRTC copies the bytes out on send anyway.
        val buf = ByteBuffer.allocate(FRAME_HEADER + len)
        buf.putDouble(offset.toDouble())          // big-endian, like DataView.setFloat64
        buf.put(body, 0, len)
        buf.flip()
        return buf
    }

    /**
     * Offset of a received frame, or null if it is too short to carry data or
     * the offset isn't a whole, non-negative number that fits [size] (every byte
     * must land inside the file the user accepted).
     */
    fun offsetOf(frame: ByteArray, size: Long = Long.MAX_VALUE): Long? {
        if (frame.size <= FRAME_HEADER) return null
        val d = ByteBuffer.wrap(frame, 0, FRAME_HEADER).double
        val len = frame.size - FRAME_HEADER
        if (!d.isFinite() || d < 0 || d != Math.floor(d) || d > MAX_SAFE || d + len > size) return null
        return d.toLong()
    }

    /**
     * A sender can name a file anything; keep it to a plain file name, shown
     * and saved the same: no folders, no control or invisible formatting
     * characters (a right-to-left override can disguise an extension), and
     * a long name loses its middle, never its extension.
     */
    fun cleanName(name: String): String {
        val s = name.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { val t = Character.getType(it); t == Character.FORMAT.toInt() || t == Character.LINE_SEPARATOR.toInt() || t == Character.PARAGRAPH_SEPARATOR.toInt() }
            .replace(Regex("[\\x00-\\x1f\\x7f:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ").trim().trimStart('.')
        if (s.length <= 200) return s.ifBlank { "file" }
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && s.length - dot <= 16) s.substring(dot) else ""
        return s.take(200 - ext.length) + ext
    }

    /** What a sender's chunk can be: chunkSizeFor() minus the header, 16 KiB-8 to 256 KiB-8. */
    const val MIN_SENT_CHUNK = 1024L
    const val MAX_SENT_CHUNK = 1L shl 20
    private const val MAX_SAFE = 9007199254740991.0   // 2^53 - 1, as on the web

    fun missingOffsets(size: Long, chunk: Long, seen: Set<Long>, cap: Int = 4096): List<Long> {
        if (chunk <= 0 || size < 0) return emptyList()
        val out = ArrayList<Long>()
        var o = 0L
        while (o < size && out.size < cap) { if (o !in seen) out += o; o += chunk }
        return out
    }

    /** Parts and texts in flight a receiver allows (text needs no Accept, so it is kept small). */
    val TEXT_MAX_PARTS = (TEXT_MAX + TEXT_PART - 2) / (TEXT_PART - 1) + 1
    const val TEXT_IN_FLIGHT = 4
}

/** Exponentially smoothed throughput, matching newMeter() in the web client. */
class Meter(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var last = now()
    private var lastBytes = 0L
    private var ewma = 0.0
    operator fun invoke(bytes: Long): Double {
        val t = now()
        val dt = t - last
        if (dt < 250) return ewma
        val inst = (bytes - lastBytes) * 1000.0 / dt
        ewma = if (ewma == 0.0) inst else ewma * 0.7 + inst * 0.3
        last = t; lastBytes = bytes
        return ewma
    }
}
