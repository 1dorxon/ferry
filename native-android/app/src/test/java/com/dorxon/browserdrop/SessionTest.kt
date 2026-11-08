package com.dorxon.browserdrop

import com.dorxon.browserdrop.proto.DataLink
import com.dorxon.browserdrop.proto.Dir
import com.dorxon.browserdrop.proto.FileSink
import com.dorxon.browserdrop.proto.FileSource
import com.dorxon.browserdrop.proto.Host
import com.dorxon.browserdrop.proto.Proto
import com.dorxon.browserdrop.proto.Session
import com.dorxon.browserdrop.proto.Transfer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {

    /** Records what the protocol reports, and answers prompts as told. */
    class FakeHost(private val answer: Boolean? = true) : Host {
        val events = mutableListOf<String>()
        val sinks = mutableListOf<MemSink>()
        var prompt: CompletableDeferred<Boolean>? = null
        var last: Transfer? = null
        var onProgress: (Transfer, Long) -> Unit = { _, _ -> }
        private var n = 0
        override fun newTransfer(dir: Dir, name: String, size: Long, peerId: String) =
            Transfer("t${++n}", dir, name, size, peerId).also { last = it; events += "new $dir $name" }
        override fun note(t: Transfer, text: String) { events += "note $text" }
        override fun progress(t: Transfer, done: Long, rate: Double) = onProgress(t, done)
        override fun finish(t: Transfer, verb: String, location: Any?) { events += "finish $verb" }
        override fun fail(t: Transfer, reason: String, retry: (() -> Unit)?) { events += "fail $reason" }
        override fun requestAccept(t: Transfer, answer: CompletableDeferred<Boolean>) {
            prompt = answer
            this.answer?.let { answer.complete(it) }
        }
        override fun openSink(t: Transfer, name: String, size: Long, mime: String) = MemSink(size).also { sinks += it }
        override fun peerName(peerId: String) = "Peer $peerId"
    }

    class MemSink(size: Long) : FileSink {
        val bytes = ByteArray(size.toInt())
        var finished = false
        var aborted = false
        override fun write(offset: Long, data: ByteArray, from: Int, len: Int) {
            System.arraycopy(data, from, bytes, offset.toInt(), len)
        }
        override suspend fun finish(): Any? { finished = true; return "mem" }
        override fun abort() { aborted = true }
    }

    class Src(override val name: String, val data: ByteArray, override val mime: String = "application/octet-stream") : FileSource {
        override val size get() = data.size.toLong()
        override fun open() = ByteArrayInputStream(data)
    }

    /** The two sessions, and a frame to lose in transit (to test receipts). */
    class Pipe {
        lateinit var a: Session
        lateinit var b: Session
        var dropFrameAt: Long? = null
    }

    @Test fun sendsAFileByteForByte() = runTest {
        val s = FakeHost(); val r = FakeHost(answer = true)
        val p = pairFixed(s, r)
        val data = Random(1).nextBytes(3 * 1024 * 1024 + 123)
        p.a.enqueue(listOf(Src("photo.jpg", data, "image/jpeg")), connecting = false)
        advanceUntilIdle()
        assertTrue(s.events.toString(), "finish Sent" in s.events)
        assertTrue(r.events.toString(), "finish Received" in r.events)
        assertArrayEquals(data, r.sinks.single().bytes)
        assertTrue(r.sinks.single().finished)
    }

    @Test fun declineFailsBothSides() = runTest {
        val s = FakeHost(); val r = FakeHost(answer = false)
        val p = pairFixed(s, r)
        p.a.enqueue(listOf(Src("a.bin", ByteArray(10))), connecting = false)
        advanceUntilIdle()
        assertTrue(s.events.toString(), "fail Peer B declined" in s.events)
        assertTrue(r.events.toString(), "fail Declined" in r.events)
        assertTrue(r.sinks.isEmpty())
    }

    @Test fun senderCancelWhileWaitingWithdrawsThePrompt() = runTest {
        val s = FakeHost(); val r = FakeHost(answer = null)   // receiver never answers
        val p = pairFixed(s, r)
        val t = p.a.enqueue(listOf(Src("a.bin", ByteArray(10))), connecting = false).single()
        // runCurrent, not advanceUntilIdle: skipping ahead in virtual time would hit
        // the 120 s accept timeout, which (correctly) reads as a decline.
        testScheduler.runCurrent()
        assertTrue(r.prompt != null)
        t.cancel!!.invoke()
        advanceUntilIdle()
        assertTrue(s.events.toString(), "fail Cancelled" in s.events)
        assertTrue(r.events.toString(), "fail Cancelled by the other device" in r.events)
    }

    @Test fun receiverCancelMidTransferStopsTheSender() = runTest {
        val s = FakeHost(); val r = FakeHost(answer = true)
        val p = pairFixed(s, r)
        // Cancel from the receiving end once a few megabytes have landed.
        var fired = false
        r.onProgress = { t, done -> if (!fired && done > 4 * 1024 * 1024) { fired = true; t.cancel!!.invoke() } }
        p.a.enqueue(listOf(Src("big.bin", ByteArray(20 * 1024 * 1024))), connecting = false)
        advanceUntilIdle()
        assertTrue(r.events.toString(), "fail Cancelled" in r.events)
        assertTrue(s.events.toString(), "fail Cancelled" in s.events)
        assertTrue(r.sinks.single().aborted)
    }

    @Test fun emptyFileCompletes() = runTest {
        val s = FakeHost(); val r = FakeHost(answer = true)
        val p = pairFixed(s, r)
        p.a.enqueue(listOf(Src("empty.txt", ByteArray(0))), connecting = false)
        advanceUntilIdle()
        assertTrue(s.events.toString(), "finish Sent" in s.events)
        assertTrue(r.events.toString(), "finish Received" in r.events)
    }

    @Test fun lostChunkIsReportedNotSilentlyAccepted() = runTest {
        val s = FakeHost(); val r = FakeHost(answer = true)
        val p = pairFixed(s, r)
        val chunk = Proto.chunkSizeFor(262144) - Proto.FRAME_HEADER
        p.dropFrameAt = chunk.toLong() * 2
        p.a.enqueue(listOf(Src("x.bin", ByteArray(chunk * 5))), connecting = false)
        advanceUntilIdle()
        assertTrue(s.events.toString(), "fail The other device did not confirm receipt" in s.events)
    }

    @Test fun textArrivesIntactInParts() = runTest {
        val s = FakeHost(); val r = FakeHost(answer = null)    // text never asks
        val p = pairFixed(s, r)
        // An emoji right on the first part boundary, as in tests/e2e_text.py.
        val text = "a".repeat(Proto.TEXT_PART - 1) + "🙂" + "line two\nünïcødé ✓\n" + "b".repeat(9000)
        p.a.enqueueText(text, connecting = false)
        advanceUntilIdle()
        assertTrue(s.events.toString(), "finish Sent" in s.events)
        assertTrue(r.events.toString(), "finish Received" in r.events)
        assertEquals(text, r.last!!.text)
        assertTrue(r.prompt == null)
    }

    @Test fun textToAnOlderFerryTimesOutWithAReason() = runTest {
        val s = FakeHost()
        val d = StandardTestDispatcher(testScheduler)
        val a = Session("B", CoroutineScope(d), s, d)
        a.link = object : DataLink {                          // a peer that ignores "text"
            override val isOpen = true
            override val bufferedAmount = 0L
            override val maxMessageSize: Int? = 262144
            override fun sendText(text: String) = true
            override fun sendBinary(buf: ByteBuffer) = true
            override suspend fun awaitBuffered(atMost: Long) {}
        }
        a.enqueueText("hello", connecting = false)
        advanceUntilIdle()
        assertTrue(s.events.toString(), "fail Peer B didn't answer — Ferry there may need an update" in s.events)
    }

    @Test fun textWaitsForTheChannelThenGoes() = runTest {
        val s = FakeHost(); val r = FakeHost()
        val p = pairFixed(s, r)
        val link = p.a.link
        p.a.link = null
        p.a.enqueueText("later", connecting = true)
        advanceUntilIdle()
        assertTrue(s.events.toString(), "note Connecting to Peer B…" in s.events)
        p.a.link = link
        p.a.onOpen()
        advanceUntilIdle()
        assertEquals("later", r.last!!.text)
    }

    @Test fun textPartsNeverSplitAnEmoji() {
        val text = "x".repeat(Proto.TEXT_PART - 1) + "🙂".repeat(5000)
        val parts = Proto.textParts(text)
        assertEquals(text, parts.joinToString(""))
        parts.forEach { assertTrue(it.isEmpty() || !Character.isLowSurrogate(it[0])) }
        assertEquals(listOf(""), Proto.textParts(""))
    }

    @Test fun chunkSizeMatchesTheWebClient() {
        assertEquals(256 * 1024, Proto.chunkSizeFor(262144 + 4096))
        assertEquals(262144 - 1024, Proto.chunkSizeFor(262144))
        assertEquals(65536 - 1024, Proto.chunkSizeFor(null))
        assertEquals(16 * 1024, Proto.chunkSizeFor(1000))
        assertEquals(65536, Proto.maxMessageSizeFromSdp("v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"))
        assertEquals(262144, Proto.maxMessageSizeFromSdp("a=sctp-port:5000\r\na=max-message-size:262144\r\n"))
    }

    @Test fun framesUseBigEndianFloat64Offsets() {
        val f = Proto.frame(3 * 262136L, byteArrayOf(1, 2, 3), 3)
        val bytes = ByteArray(f.remaining()); f.get(bytes)
        // Same bytes as `new DataView(b).setFloat64(0, 786408)` in a browser.
        val expect = ByteBuffer.allocate(8).putDouble(786408.0).array()
        assertArrayEquals(expect, bytes.copyOf(8))
        assertEquals(786408L, Proto.offsetOf(bytes))
    }

    /** Builds the pair with each link delivering to the opposite session. */
    private fun TestScope.pairFixed(sendHost: FakeHost, recvHost: FakeHost): Pipe {
        val d = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(d)
        lateinit var a: Session
        lateinit var b: Session
        val p = Pipe()
        fun link(to: () -> Session) = object : DataLink {
            override val isOpen = true
            override val bufferedAmount = 0L
            override val maxMessageSize: Int? = 262144
            override fun sendText(text: String): Boolean { scope.launch { to().onText(text) }; return true }
            override fun sendBinary(buf: ByteBuffer): Boolean {
                val copy = ByteArray(buf.remaining()); buf.get(copy)
                scope.launch {
                    if (Proto.offsetOf(copy) == p.dropFrameAt) { p.dropFrameAt = null; return@launch }
                    to().onBinary(copy)
                }
                return true
            }
            override suspend fun awaitBuffered(atMost: Long) {}
        }
        a = Session("B", scope, sendHost, d).also { it.link = link { b } }
        b = Session("A", scope, recvHost, d).also { it.link = link { a } }
        p.a = a; p.b = b
        return p
    }
}
