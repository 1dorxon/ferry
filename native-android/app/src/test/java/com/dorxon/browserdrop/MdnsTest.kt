package com.dorxon.browserdrop

import com.dorxon.browserdrop.net.Mdns
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class MdnsTest {
    private val host = "3f9a1c2e-7b44-4d0e-9a51-0c6f2b8d1e77.local"

    private fun name(n: String) = ByteArrayOutputStream().apply {
        n.split('.').forEach { write(it.length); write(it.toByteArray()) }; write(0)
    }.toByteArray()

    /** A response like Chrome's: the question echoed, then an answer pointing back at it. */
    private fun response(type: Int, rdata: ByteArray, answerName: ByteArray? = null) = ByteArrayOutputStream().apply {
        write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 1, 0, 1, 0, 0, 0, 0))
        write(name(host)); write(byteArrayOf(0, type.toByte(), 0, 1))
        write(answerName ?: byteArrayOf(0xc0.toByte(), 12))                  // compressed: points at the question
        write(byteArrayOf(0, type.toByte(), 0x80.toByte(), 1, 0, 0, 0, 120, 0, rdata.size.toByte()))
        write(rdata)
    }.toByteArray()

    @Test fun queryIsAStandardQuestion() {
        val q = Mdns.query(host, 1, unicastResponse = true)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0), q.copyOf(12))
        assertArrayEquals(name(host), q.copyOfRange(12, 12 + name(host).size))
        assertArrayEquals(byteArrayOf(0, 1, 0x80.toByte(), 1), q.copyOfRange(q.size - 4, q.size))   // A, IN + QU
    }

    @Test fun parsesCompressedARecord() {
        val ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 23)
        assertEquals(InetAddress.getByAddress(ip), Mdns.parseAnswer(response(1, ip), host))
    }

    @Test fun parsesAaaaRecordWithFullName() {
        val ip = InetAddress.getByName("fe80::1c2b:3aff:fe4d:5e6f").address
        assertEquals(InetAddress.getByAddress(ip), Mdns.parseAnswer(response(28, ip, name(host)), host))
    }

    @Test fun ignoresOtherNamesQueriesAndJunk() {
        val ip = byteArrayOf(10, 0, 0, 5)
        assertNull(Mdns.parseAnswer(response(1, ip), "someone-else.local"))
        assertNull(Mdns.parseAnswer(Mdns.query(host, 1, false), host))         // a query, not a response
        assertNull(Mdns.parseAnswer(byteArrayOf(1, 2, 3), host))
        assertNull(Mdns.parseAnswer(response(1, ip).copyOf(40), host))          // truncated
    }
}
