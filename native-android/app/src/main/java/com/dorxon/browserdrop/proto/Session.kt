package com.dorxon.browserdrop.proto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.min

/** The data channel, as the protocol needs it. RTC details live elsewhere. */
interface DataLink {
    val isOpen: Boolean
    val bufferedAmount: Long
    /** The peer's SCTP message limit; null if not known yet. */
    val maxMessageSize: Int?
    fun sendText(text: String): Boolean
    fun sendBinary(buf: ByteBuffer): Boolean
    /** Suspend until at most [atMost] bytes are queued, or the channel closes. */
    suspend fun awaitBuffered(atMost: Long)
}

/** A file to send. */
interface FileSource {
    val name: String
    val size: Long
    val mime: String
    fun open(): InputStream
}

/** Where a received file goes. Writes are positional and may be queued. */
interface FileSink {
    fun write(offset: Long, data: ByteArray, from: Int, len: Int)
    /** Flush, close and publish the file. Returns where it ended up (for "Open"). */
    suspend fun finish(): Any?
    fun abort()
}

enum class Dir { OUT, IN }

/**
 * Everything the protocol tells the outside world. Implemented by the engine
 * (UI state, notifications, storage) and by fakes in the tests.
 */
interface Host {
    fun newTransfer(dir: Dir, name: String, size: Long, peerId: String): Transfer
    fun note(t: Transfer, text: String)
    fun progress(t: Transfer, done: Long, rate: Double)
    fun finish(t: Transfer, verb: String, location: Any?)
    fun fail(t: Transfer, reason: String, retry: (() -> Unit)? = null)
    /** Ask the user; complete [answer] with their choice. May be completed remotely too. */
    fun requestAccept(t: Transfer, answer: CompletableDeferred<Boolean>)
    fun openSink(t: Transfer, name: String, size: Long, mime: String): FileSink
    fun peerName(peerId: String): String?
    /** A row for text (the clipboard). Engines that show text override this. */
    fun newText(dir: Dir, text: String, peerId: String): Transfer =
        newTransfer(dir, "Text", text.length.toLong(), peerId).also { it.text = text }
}

/** One transfer, as the protocol tracks it. The engine mirrors it into UI state. */
class Transfer(
    val key: String,
    val dir: Dir,
    val name: String,
    val size: Long,
    val peerId: String,
) {
    @Volatile var cancelled = false
    /** Set by whoever owns it; until then cancelling just fails the row. */
    var cancel: (() -> Unit)? = null
    /** Why it was stopped from outside the send loop (connection died, etc.). */
    var stopReason: String? = null
    /** Set for text (the clipboard) rather than a file. */
    var text: String? = null
}

private class Stop(msg: String) : Exception(msg)

private class Rx(
    val id: String,
    val size: Long,
    val t: Transfer,
    val sink: FileSink,
) {
    val seen = HashSet<Long>()
    var received = 0L
    var ending = false
    val meter = Meter()
}

/**
 * The transfer protocol for one peer: a port of the Conn class in the web
 * client minus the RTC plumbing. Every method must run on the engine's single
 * thread ([scope] is confined to it); file I/O hops to Dispatchers.IO.
 */
class Session(
    val peerId: String,
    private val scope: CoroutineScope,
    private val host: Host,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    var link: DataLink? = null

    private data class Job(val src: FileSource, val t: Transfer)
    private val queue = ArrayDeque<Job>()
    private var sending = false
    private var sendingT: Transfer? = null
    private var seq = 0
    private val verdicts = HashMap<String, CompletableDeferred<Boolean>>()
    private var pendingVerdictId: String? = null
    private var pendingAccept: CompletableDeferred<Boolean>? = null
    private var acceptWithdrawn: String? = null   // why the prompt was taken away, if it was
    private var replyWaiter: Pair<String, CompletableDeferred<JSONObject?>>? = null
    private val doneIds = HashSet<String>()
    private var rx: Rx? = null
    private val texts = ArrayDeque<Transfer>()                           // waiting for the channel
    private val textOut = HashMap<String, Pair<Transfer, kotlinx.coroutines.Job>>()  // awaiting textgot
    private class TextIn(val parts: Array<String?>, val at: Long) { var got = 0; var len = 0 }
    private var receiving = false                 // an offer is being considered or opened
    private val textIn = LinkedHashMap<String, TextIn>()   // oldest first, for eviction

    val busy: Boolean get() = sending || rx != null || queue.isNotEmpty() || pendingAccept != null ||
        texts.isNotEmpty() || textOut.isNotEmpty()
    val hasQueued: Boolean get() = queue.isNotEmpty() || texts.isNotEmpty()

    /* ---------------------------------------------------------- sending */

    fun enqueue(files: List<FileSource>, connecting: Boolean): List<Transfer> {
        val who = host.peerName(peerId) ?: "peer"
        val out = files.map { f ->
            val t = host.newTransfer(Dir.OUT, f.name, f.size, peerId)
            if (connecting) host.note(t, "Connecting to $who…")
            t.cancel = { cancel(t) }
            queue.addLast(Job(f, t))
            t
        }
        pump()
        return out
    }

    /** Cancel a transfer whether queued or streaming, and tell the far side. */
    fun cancel(t: Transfer) {
        val i = queue.indexOfFirst { it.t === t }
        if (i >= 0) { queue.removeAt(i); host.fail(t, "Cancelled"); return }
        if (sendingT === t) {
            t.cancelled = true                     // the send loop notices on its next chunk
            sendCtrl(JSONObject().put("t", "cancel"))
            pendingVerdictId?.let { verdicts[it]?.complete(false) }
        }
    }

    /** No connection could be formed: fail everything queued, offering a retry. */
    fun fallback(reason: String, detail: String = "", retry: (FileSource) -> Unit, retryText: (String) -> Unit = {}) {
        while (queue.isNotEmpty()) {
            val (src, t) = queue.removeFirst()
            host.fail(t, "$reason — both devices must be on the same network$detail") { retry(src) }
        }
        while (texts.isNotEmpty()) {
            val t = texts.removeFirst()
            val text = t.text ?: continue
            host.fail(t, "$reason — both devices must be on the same network$detail") { retryText(text) }
        }
    }

    fun noteQueued(text: String) { for (j in queue) host.note(j.t, text); for (t in texts) host.note(t, text) }

    fun onOpen() { flushTexts(); pump() }

    /* ------------------------------------------------- text: the clipboard */

    fun enqueueText(text: String, connecting: Boolean): Transfer {
        val who = host.peerName(peerId) ?: "peer"
        val t = host.newText(Dir.OUT, text, peerId)
        t.cancel = { texts.remove(t); host.fail(t, "Cancelled") }
        texts.addLast(t)
        if (connecting) host.note(t, "Connecting to $who…")
        flushTexts()
        return t
    }

    private fun flushTexts() {
        if (link?.isOpen != true) return
        while (texts.isNotEmpty()) sendTextNow(texts.removeFirst())
    }

    private fun sendTextNow(t: Transfer) {
        val text = t.text ?: return
        val who = host.peerName(peerId) ?: "the other device"
        val id = "x${++seq}"
        val parts = Proto.textParts(text)
        val ok = parts.withIndex().all { (i, p) ->
            sendCtrl(JSONObject().put("t", "text").put("id", id).put("part", i).put("parts", parts.size).put("text", p))
        }
        if (!ok) { host.fail(t, "Connection lost"); return }
        host.note(t, "Sending to $who…")
        val timer = scope.launch {
            delay(Proto.TEXT_ACK_MS)
            textOut.remove(id)
            host.fail(t, "$who didn't answer — Ferry there may need an update")
        }
        textOut[id] = t to timer
        t.cancel = { textOut.remove(id)?.second?.cancel(); host.fail(t, "Cancelled") }
    }

    private fun onTextPart(msg: JSONObject) {
        val id = msg.optString("id")
        val parts = msg.optInt("parts", 0)
        val part = msg.optInt("part", -1)
        if (!msg.has("text") || id.length > 64 || parts < 1 || parts > Proto.TEXT_MAX_PARTS || part < 0 || part >= parts) return
        val text = msg.optString("text")
        if (text.length > Proto.TEXT_PART) return
        // Text needs no Accept, so a peer may only keep a few unfinished ones here,
        // and those expire.
        val now = System.currentTimeMillis()
        textIn.entries.removeAll { now - it.value.at > Proto.TEXT_ACK_MS }
        if (id !in textIn && textIn.size >= Proto.TEXT_IN_FLIGHT) textIn.remove(textIn.keys.first())
        val a = textIn.getOrPut(id) { TextIn(arrayOfNulls(parts), now) }
        if (a.parts.size != parts || a.parts[part] != null) return
        a.parts[part] = text
        a.got++
        a.len += text.length
        if (a.len > Proto.TEXT_MAX * 2) { textIn.remove(id); return }
        if (a.got < parts) return
        textIn.remove(id)
        sendCtrl(JSONObject().put("t", "textgot").put("id", id))
        val t = host.newText(Dir.IN, a.parts.joinToString(""), peerId)
        host.finish(t, "Received", null)
    }

    private fun onTextGot(msg: JSONObject) {
        val (t, timer) = textOut.remove(msg.optString("id")) ?: return
        timer.cancel()
        host.finish(t, "Sent", null)
    }

    private fun pump() {
        if (sending || link?.isOpen != true) return
        val job = queue.removeFirstOrNull() ?: return
        sending = true
        scope.launch {
            try {
                sendOne(job.src, job.t)
            } catch (e: Stop) {
                host.fail(job.t, job.t.stopReason ?: e.message ?: "Transfer failed")
            } catch (e: Exception) {
                host.fail(job.t, job.t.stopReason ?: e.message ?: "Transfer failed")
            } finally {
                sending = false
                sendingT = null
                pump()
            }
        }
    }

    private suspend fun sendOne(src: FileSource, t: Transfer) {
        val link = link ?: throw Stop("Connection lost")
        val id = "f${++seq}"
        val who = host.peerName(peerId) ?: "the other device"

        // Claim it now: between the announcement and the answer this is still the
        // in-flight transfer, and Cancel has to reach it there.
        sendingT = t
        val announced = link.sendText(
            JSONObject().put("t", "file").put("id", id).put("name", src.name)
                .put("size", src.size).put("type", src.mime).toString()
        )
        if (!announced) throw Stop("Connection lost")

        // Nothing leaves this device until the far side agrees to receive it.
        host.note(t, "Waiting for $who to accept…")
        pendingVerdictId = id
        val accepted = awaitVerdict(id)
        pendingVerdictId = null
        if (t.cancelled) throw Stop("Cancelled")
        if (!accepted) throw Stop("$who declined")

        val chunk = Proto.chunkSizeFor(link.maxMessageSize) - Proto.FRAME_HEADER
        val meter = Meter()
        var sent = 0L
        val buf = ByteArray(chunk)
        val input = withContext(io) { src.open() }
        try {
            while (sent < src.size) {
                if (t.cancelled) throw Stop("Cancelled")
                if (!link.isOpen) throw Stop("Connection lost")
                // Backpressure: let SCTP drain rather than queueing the whole file.
                if (link.bufferedAmount > Proto.HIGH_WATER) link.awaitBuffered(Proto.LOW_WATER)
                val want = min(chunk.toLong(), src.size - sent).toInt()
                val n = withContext(io) { readFully(input, buf, want) }
                if (n <= 0) throw Stop("Could not read the file")
                if (!link.sendBinary(Proto.frame(sent, buf, n))) {
                    // A full send buffer reports failure rather than queueing; wait and retry once.
                    link.awaitBuffered(Proto.LOW_WATER)
                    if (!link.sendBinary(Proto.frame(sent, buf, n))) throw Stop("Connection lost")
                }
                sent += n
                host.progress(t, sent, meter(sent))
            }
        } finally {
            withContext(io) { runCatching { input.close() } }
        }
        // Let the tail drain, then have the receiver confirm every byte arrived,
        // so "Sent" means delivered rather than merely queued.
        while (link.bufferedAmount > 0 && link.isOpen) delay(40)
        val reply = ask(
            JSONObject().put("t", "sent").put("id", id).put("size", src.size).put("chunk", chunk)
        )
        if (t.cancelled) throw Stop("Cancelled")
        if (reply?.optString("t") != "got") throw Stop("The other device did not confirm receipt")
        sendingT = null
        host.finish(t, "Sent", null)
    }

    private fun readFully(input: InputStream, buf: ByteArray, want: Int): Int {
        var got = 0
        while (got < want) {
            val n = input.read(buf, got, want - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private suspend fun awaitVerdict(id: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        verdicts[id] = d
        val v = withTimeoutOrNull(Proto.VERDICT_TIMEOUT_MS) { d.await() } ?: false
        verdicts.remove(id)
        return v
    }

    private suspend fun ask(msg: JSONObject): JSONObject? {
        val d = CompletableDeferred<JSONObject?>()
        replyWaiter = msg.getString("id") to d
        if (!sendCtrl(msg)) { replyWaiter = null; return null }
        val r = withTimeoutOrNull(Proto.REPLY_TIMEOUT_MS) { d.await() }
        replyWaiter = null
        return r
    }

    private fun sendCtrl(obj: JSONObject): Boolean {
        val l = link ?: return false
        if (!l.isOpen) return false
        return runCatching { l.sendText(obj.toString()) }.getOrDefault(false)
    }

    /* -------------------------------------------------------- receiving */

    fun onText(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "file" -> {
                // One offer at a time per device, with a sane id and size.
                val id = msg.optString("id")
                val size = msg.optLong("size", -1L)
                if (id.isEmpty() || id.length > 64 || size < 0 || pendingAccept != null || rx != null || receiving) {
                    if (id.isNotEmpty() && id.length <= 64) sendCtrl(JSONObject().put("t", "reject").put("id", id))
                } else {
                    receiving = true
                    scope.launch { try { beginReceive(msg) } finally { receiving = false } }
                }
            }
            "accept", "reject" -> verdicts[msg.optString("id")]?.complete(msg.optString("t") == "accept")
            "need", "got" -> replyWaiter?.let { (id, d) -> if (msg.optString("id") == id) d.complete(msg) }
            "sent" -> onSent(msg)
            "cancel" -> remoteCancel()
            "text" -> onTextPart(msg)
            "textgot" -> onTextGot(msg)
        }
    }

    /** One framed chunk, placed by its offset. [frame] must be a private copy. */
    fun onBinary(frame: ByteArray) {
        val rx = rx ?: return
        if (frame.size <= Proto.FRAME_HEADER) return
        val off = Proto.offsetOf(frame, rx.size) ?: run {
            sendCtrl(JSONObject().put("t", "cancel"))
            discardRx("The other device sent more than it offered")
            return
        }
        if (!rx.seen.add(off)) return              // a resend of something that did arrive
        val len = frame.size - Proto.FRAME_HEADER
        rx.received += len
        rx.sink.write(off, frame, Proto.FRAME_HEADER, len)
        host.progress(rx.t, rx.received, rx.meter(rx.received))
        if (rx.received >= rx.size && !rx.ending) { rx.ending = true; endReceive() }
    }

    private fun onSent(msg: JSONObject) {
        val id = msg.optString("id")
        val rx = rx
        if (rx != null && rx.id == id) {
            // Only the accepted size, in chunks the size senders really use.
            val chunk = msg.optLong("chunk", 0L)
            if (msg.optLong("size", -1L) != rx.size || chunk < Proto.MIN_SENT_CHUNK || chunk > Proto.MAX_SENT_CHUNK) {
                sendCtrl(JSONObject().put("t", "cancel"))
                discardRx("The other device sent a bad transfer")
                return
            }
            val missing = Proto.missingOffsets(rx.size, chunk, rx.seen)
            if (missing.isNotEmpty()) {
                sendCtrl(JSONObject().put("t", "need").put("id", id).put("offsets", JSONArray(missing)))
                return
            }
        }
        if (id in doneIds) sendCtrl(JSONObject().put("t", "got").put("id", id))
    }

    private fun remoteCancel() {
        sendingT?.cancelled = true
        pendingVerdictId?.let { verdicts[it]?.complete(false) }
        // Dismiss an Accept/Decline prompt the user never answered.
        pendingAccept?.let { acceptWithdrawn = "Cancelled by the other device"; it.complete(false) }
        discardRx("Cancelled by the other device")
    }

    private fun discardRx(reason: String) {
        val r = rx ?: return
        rx = null
        r.sink.abort()
        host.fail(r.t, reason)
    }

    private suspend fun beginReceive(msg: JSONObject) {
        val id = msg.optString("id")
        // Cleaned once: what is shown at Accept is exactly what is saved.
        val name = Proto.cleanName(msg.optString("name"))
        val size = msg.optLong("size")
        val type = msg.optString("type")
        val t = host.newTransfer(Dir.IN, name, size, peerId)

        // Room membership is grouping, not permission: a shared public IP can put
        // a stranger in the room. Every incoming file needs a deliberate yes.
        val answer = CompletableDeferred<Boolean>()
        pendingAccept = answer
        acceptWithdrawn = null
        host.requestAccept(t, answer)
        val allowed = answer.await()
        val withdrawn = acceptWithdrawn
        pendingAccept = null
        if (!allowed) {
            if (withdrawn == null) sendCtrl(JSONObject().put("t", "reject").put("id", id))
            host.fail(t, withdrawn ?: "Declined")
            return
        }

        val sink = try {
            withContext(io) { host.openSink(t, name, size, type) }
        } catch (e: Exception) {
            sendCtrl(JSONObject().put("t", "reject").put("id", id))
            host.fail(t, "Couldn't save here: ${e.message ?: "storage unavailable"}")
            return
        }
        // Cancelling a receive tells the sender to stop, so nobody keeps pushing
        // bytes nobody wants.
        t.cancel = {
            sendCtrl(JSONObject().put("t", "cancel"))
            discardRx("Cancelled")
        }
        rx = Rx(id, size, t, sink)
        sendCtrl(JSONObject().put("t", "accept").put("id", id))   // only now may bytes flow
        if (size == 0L) { rx!!.ending = true; endReceive() }
    }

    private fun endReceive() {
        val r = rx ?: return
        rx = null
        doneIds += r.id
        sendCtrl(JSONObject().put("t", "got").put("id", r.id))
        scope.launch {
            try {
                val where = r.sink.finish()
                host.finish(r.t, "Received", where)
            } catch (e: Exception) {
                host.fail(r.t, "Couldn't save the file: ${e.message ?: "storage error"}")
            }
        }
    }

    /* ----------------------------------------------------------- teardown */

    fun failAll(reason: String) {
        while (queue.isNotEmpty()) host.fail(queue.removeFirst().t, reason)
        while (texts.isNotEmpty()) host.fail(texts.removeFirst(), reason)
        for ((t, timer) in textOut.values.toList()) { timer.cancel(); host.fail(t, reason) }
        textOut.clear()
        textIn.clear()
        sendingT?.let { it.stopReason = reason; it.cancelled = true }
        sendingT = null
        verdicts.values.forEach { it.complete(false) }
        replyWaiter?.second?.complete(null)
        pendingAccept?.let { acceptWithdrawn = reason; it.complete(false) }
        discardRx(reason)
    }
}
