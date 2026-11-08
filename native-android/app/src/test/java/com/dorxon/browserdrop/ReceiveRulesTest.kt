package com.dorxon.browserdrop

import com.dorxon.browserdrop.proto.DataLink
import com.dorxon.browserdrop.proto.Proto
import com.dorxon.browserdrop.proto.Session
import com.dorxon.browserdrop.rtc.RtcLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * What a receiver accepts from a peer, which may be anyone sharing the room
 * (see the security audit): everything stays inside what the user accepted,
 * and what text can make us hold stays small.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveRulesTest {

    /** A receiving session whose peer is this test: we feed it messages and read its replies. */
    private fun TestScope.receiver(host: SessionTest.FakeHost): Pair<Session, MutableList<JSONObject>> {
        val d = StandardTestDispatcher(testScheduler)
        val sent = mutableListOf<JSONObject>()
        val s = Session("P", CoroutineScope(d), host, d)
        s.link = object : DataLink {
            override val isOpen = true
            override val maxMessageSize = 262144
            override fun sendText(text: String) = true.also { sent += JSONObject(text) }
            override fun sendBinary(buf: ByteBuffer) = true
            override val bufferedAmount = 0L
            override suspend fun awaitBuffered(atMost: Long) {}
        }
        return s to sent
    }

    private fun frame(offset: Long, vararg body: Byte): ByteArray {
        val b = Proto.frame(offset, body, body.size)
        return ByteArray(b.remaining()).also { b.get(it) }
    }

    private fun offer(id: String, name: String, size: Long) =
        JSONObject().put("t", "file").put("id", id).put("name", name).put("size", size).put("type", "text/plain").toString()

    @Test fun aFrameBeyondTheAcceptedSizeCancels() = runTest {
        val host = SessionTest.FakeHost(answer = true)
        val (r, sent) = receiver(host)
        r.onText(offer("f1", "tiny.txt", 1)); advanceUntilIdle()
        r.onBinary(frame(30_000_000L, 65)); advanceUntilIdle()
        assertTrue(host.events.toString(), "fail The other device sent more than it offered" in host.events)
        assertTrue(sent.any { it.optString("t") == "cancel" })
        assertTrue(host.sinks.single().aborted)
    }

    @Test fun aSentMessageWithABadChunkCancelsInsteadOfLooping() = runTest {
        val host = SessionTest.FakeHost(answer = true)
        val (r, sent) = receiver(host)
        r.onText(offer("f1", "a.bin", 100_000)); advanceUntilIdle()
        r.onText(JSONObject().put("t", "sent").put("id", "f1").put("size", 100_000).put("chunk", 0).toString())
        advanceUntilIdle()
        assertTrue(host.events.toString(), "fail The other device sent a bad transfer" in host.events)
        assertTrue(sent.any { it.optString("t") == "cancel" })
    }

    @Test fun aSecondOfferWhileOneIsAskingIsRefused() = runTest {
        val host = SessionTest.FakeHost(answer = null)             // the user hasn't answered yet
        val (r, sent) = receiver(host)
        r.onText(offer("f1", "one.txt", 10)); advanceUntilIdle()
        r.onText(offer("f2", "two.txt", 10)); advanceUntilIdle()
        assertEquals(1, host.events.count { it.startsWith("new ") })
        assertTrue(sent.any { it.optString("t") == "reject" && it.optString("id") == "f2" })
    }

    @Test fun onlyAFewUnfinishedTextsAreKept() = runTest {
        val host = SessionTest.FakeHost(answer = null)
        val (r, _) = receiver(host)
        fun part(id: String, i: Int) = JSONObject().put("t", "text").put("id", id).put("part", i).put("parts", 2).put("text", "x").toString()
        for (i in 0 until 10) r.onText(part("x$i", 0))
        r.onText(part("x0", 1))                                   // evicted long ago: never completes
        r.onText(part("x9", 1))                                   // still held: completes
        advanceUntilIdle()
        assertEquals(1, host.events.count { it == "finish Received" })
        // A part longer than any sender makes is ignored outright.
        r.onText(JSONObject().put("t", "text").put("id", "big").put("part", 0).put("parts", 1).put("text", "y".repeat(Proto.TEXT_PART + 1)).toString())
        advanceUntilIdle()
        assertEquals(1, host.events.count { it == "finish Received" })
    }

    @Test fun namesAreShownAndSavedTheSame() {
        assertEquals("holidaygpj.exe", Proto.cleanName("holiday‮gpj.exe"))
        assertEquals("passwd", Proto.cleanName("../../etc/passwd"))
        val long = "report" + "x".repeat(300) + ".exe"
        assertTrue(Proto.cleanName(long).endsWith(".exe"))
        assertEquals(200, Proto.cleanName(long).length)
    }

    @Test fun onlyLocalNetworkCandidatesAreUsed() {
        assertTrue(RtcLink.localCandidate("candidate:1 1 udp 2122260223 192.168.1.20 50000 typ host"))
        assertTrue(RtcLink.localCandidate("candidate:1 1 udp 2122260223 4f1c2b9a-0000.local 50000 typ host"))
        assertTrue(RtcLink.localCandidate("candidate:1 1 udp 2122260223 fe80::1 50000 typ host"))
        assertFalse(RtcLink.localCandidate("candidate:1 1 udp 2122260223 203.0.113.9 40000 typ host"))
        assertFalse(RtcLink.localCandidate("candidate:1 1 udp 1686052607 192.168.1.20 40000 typ srflx raddr 0.0.0.0 rport 0"))
        assertFalse(RtcLink.localCandidate("candidate:1 1 udp 41885439 198.51.100.7 3478 typ relay raddr 0.0.0.0 rport 0"))
        val sdp = "v=0\r\na=candidate:1 1 udp 1 203.0.113.9 1 typ host\r\na=candidate:2 1 udp 1 10.0.0.5 2 typ host\r\n"
        assertEquals("v=0\r\na=candidate:2 1 udp 1 10.0.0.5 2 typ host\r\n", RtcLink.localOnlySdp(sdp))
    }
}
