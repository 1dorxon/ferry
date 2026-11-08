package com.dorxon.browserdrop

import com.dorxon.browserdrop.SessionTest.FakeHost
import com.dorxon.browserdrop.SessionTest.Src
import com.dorxon.browserdrop.proto.DataLink
import com.dorxon.browserdrop.proto.Proto
import com.dorxon.browserdrop.proto.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Replays message traces recorded between two real browsers running the web
 * client (tests/…/web-traces.json, captured with Playwright) against this
 * protocol implementation, in both roles:
 *
 *  - as RECEIVER: feed it what the browser sender sent; it must answer exactly
 *    what the browser receiver answered.
 *  - as SENDER:   play the browser receiver's answers back; it must send exactly
 *    what the browser sender sent (same control messages, same framing).
 *
 * If the web client's protocol changes, re-record the traces and this fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebConformanceTest {

    private val traces = JSONObject(javaClass.getResource("/web-traces.json")!!.readText())

    /** A trace side as comparable values: control messages as maps, frame runs as summaries. */
    private fun side(scen: String, who: String): List<Any> {
        val arr = traces.getJSONObject(scen).getJSONArray(who)
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            if (m.getString("k") == "text") norm(JSONObject(m.getString("d")))
            else "frames n=${m.getInt("n")} first=${m.getLong("first")} last=${m.getLong("last")} lens=${m.getJSONArray("lens")}"
        }
    }

    /** Control message as a map; file names differ between runs so they're blanked. */
    private fun norm(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { k -> if (k == "name") "*" else o.get(k).let { if (it is Number) it.toLong() else it } }

    /** Records what the session under test sends, squashing frames like the capture did. */
    private class Recorder {
        val out = mutableListOf<Any>()
        private var run: IntArray? = null
        private var first = 0L; private var last = 0L; private val lens = sortedSetOf<Int>()
        fun text(s: String, norm: (JSONObject) -> Any) { flush(); out += norm(JSONObject(s)) }
        fun frame(off: Long, len: Int) {
            if (run == null) { run = intArrayOf(0); first = off; lens.clear() }
            run!![0]++; last = off; lens += len
        }
        fun flush() {
            run?.let { out += "frames n=${it[0]} first=$first last=$last lens=${JSONArray(lens.toList())}" }
            run = null
        }
    }

    private fun TestScope.session(host: FakeHost, rec: Recorder, inbox: (Session) -> Unit = {}): Pair<Session, CoroutineScope> {
        val d = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(d)
        val s = Session("peer", scope, host, d)
        s.link = object : DataLink {
            override val isOpen = true
            override val bufferedAmount = 0L
            override val maxMessageSize: Int? = 262144     // what Chrome advertises
            override fun sendText(text: String): Boolean { rec.text(text, ::norm); scope.launch { inbox(s) }; return true }
            override fun sendBinary(buf: ByteBuffer): Boolean {
                val off = buf.duplicate().double.toLong()
                rec.frame(off, buf.remaining() - Proto.FRAME_HEADER); return true
            }
            override suspend fun awaitBuffered(atMost: Long) {}
        }
        return s to scope
    }

    /**
     * Feed a browser sender's trace into [s] as if it arrived over the wire. A real
     * sender waits for "accept" before any bytes, so let each control message be
     * fully handled before moving on.
     */
    private fun TestScope.feedSender(scen: String, s: Session, upToFrames: Int = Int.MAX_VALUE) {
        val arr = traces.getJSONObject(scen).getJSONArray("sender")
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            if (m.getString("k") == "text") { s.onText(m.getString("d")); testScheduler.runCurrent(); continue }
            val chunk = 261112L
            var off = m.getLong("first")
            var sent = 0
            val lens = m.getJSONArray("lens")
            val total = traces.getJSONObject(scen).getJSONArray("sender").getJSONObject(0)
                .let { JSONObject(it.getString("d")).getLong("size") }
            while (off <= m.getLong("last") && sent < upToFrames) {
                val len = minOf(chunk, total - off).toInt()
                assertTrue(lens.toString(), (0 until lens.length()).any { lens.getInt(it) == len } || sent > 0)
                val frame = ByteArray(Proto.FRAME_HEADER + len)
                ByteBuffer.wrap(frame).putDouble(off.toDouble())
                s.onBinary(frame)
                off += chunk; sent++
            }
        }
    }

    /* ------------------------------------------------ this app as receiver */

    @Test fun receiverAccept() = runTest {
        val rec = Recorder(); val host = FakeHost(answer = true)
        val (s, _) = session(host, rec)
        feedSender("accept", s); advanceUntilIdle(); rec.flush()
        assertEquals(side("accept", "receiver"), rec.out)
        assertTrue(host.events.toString(), "finish Received" in host.events)
    }

    @Test fun receiverDecline() = runTest {
        val rec = Recorder(); val host = FakeHost(answer = false)
        val (s, _) = session(host, rec)
        feedSender("decline", s); advanceUntilIdle(); rec.flush()
        assertEquals(side("decline", "receiver"), rec.out)
    }

    @Test fun receiverSenderCancelsWhileAsking() = runTest {
        val rec = Recorder(); val host = FakeHost(answer = null)
        val (s, _) = session(host, rec)
        feedSender("sender_cancel_waiting", s); testScheduler.runCurrent(); rec.flush()
        assertEquals(side("sender_cancel_waiting", "receiver"), rec.out)
        assertTrue(host.events.toString(), "fail Cancelled by the other device" in host.events)
    }

    @Test fun receiverCancelsMidway() = runTest {
        val rec = Recorder(); val host = FakeHost(answer = true)
        val (s, _) = session(host, rec)
        feedSender("receiver_cancel_mid", s, upToFrames = 20)
        advanceUntilIdle()
        host.last!!.cancel!!.invoke(); advanceUntilIdle(); rec.flush()
        // The browser receiver sent exactly: accept, cancel.
        assertEquals(side("receiver_cancel_mid", "receiver"), rec.out)
        assertTrue(host.sinks.single().aborted)
    }

    @Test fun receiverEmptyFile() = runTest {
        val rec = Recorder(); val host = FakeHost(answer = true)
        val (s, _) = session(host, rec)
        feedSender("empty", s); advanceUntilIdle(); rec.flush()
        assertEquals(side("empty", "receiver"), rec.out)
    }

    /* -------------------------------------------------- this app as sender */

    /** Answer like the browser receiver did: its replies, in order, one per message we send. */
    private fun replies(scen: String): ArrayDeque<String> {
        val arr = traces.getJSONObject(scen).getJSONArray("receiver")
        return ArrayDeque((0 until arr.length()).map { arr.getJSONObject(it).getString("d") })
    }

    private fun TestScope.senderRun(scen: String, size: Int): Pair<Recorder, FakeHost> {
        val rec = Recorder(); val host = FakeHost()
        val answers = replies(scen)
        // The browser receiver answers "file" with accept/reject and "sent" with got.
        val (s, _) = session(host, rec) { sess ->
            val lastText = rec.out.lastOrNull { it is Map<*, *> } as? Map<*, *> ?: return@session
            when (lastText["t"]) {
                "file", "sent" -> answers.removeFirstOrNull()?.let { sess.onText(it) }
            }
        }
        s.enqueue(listOf(Src("x", ByteArray(size))), connecting = false)
        s.onOpen()
        advanceUntilIdle(); rec.flush()
        return rec to host
    }

    @Test fun senderAccept() = runTest {
        val (rec, host) = senderRun("accept", 3145851)
        assertEquals(side("accept", "sender"), rec.out)
        assertTrue(host.events.toString(), "finish Sent" in host.events)
    }

    @Test fun senderDeclined() = runTest {
        val (rec, host) = senderRun("decline", 1000)
        assertEquals(side("decline", "sender"), rec.out)
        assertTrue(host.events.toString(), "fail Peer peer declined" in host.events)
    }

    @Test fun senderEmptyFile() = runTest {
        val (rec, _) = senderRun("empty", 0)
        assertEquals(side("empty", "sender"), rec.out)
    }
}
