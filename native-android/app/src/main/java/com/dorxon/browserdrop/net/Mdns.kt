package com.dorxon.browserdrop.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Resolves the `<uuid>.local` names browsers put in WebRTC candidates.
 *
 * Chrome and Safari hide a page's local IP behind a random mDNS name and answer
 * for it on the LAN. Browsers resolve each other's names themselves; Android's
 * resolver often can't (it depends on the version and the vendor), and without
 * the address this side can't start connectivity checks -- the connection then
 * hinges entirely on the browser's checks getting through. So ask the LAN
 * directly: one mDNS query, multicast on the Wi-Fi/Ethernet interface.
 */
object Mdns {
    private const val TAG = "ferry.mdns"
    private val GROUP4: InetAddress = InetAddress.getByName("224.0.0.251")
    private val GROUP6_BYTES = byteArrayOf(0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xfb.toByte())

    /** Blocking; call off the main thread. Null if nobody answered. */
    fun resolve(context: Context, host: String, timeoutMs: Int = 2000): InetAddress? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        // Many phones drop incoming multicast unless an app holds this lock.
        val lock = runCatching { wifi?.createMulticastLock("ferry-mdns")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            for (iface in lanInterfaces(context)) {
                for (v6 in listOf(false, true)) {
                    val group = if (v6) Inet6Address.getByAddress(null, GROUP6_BYTES, iface) else GROUP4
                    if (v6 && iface.inetAddresses.toList().none { it is Inet6Address }) continue
                    if (!v6 && iface.inetAddresses.toList().none { it is Inet4Address }) continue
                    ask(iface, group, host, timeoutMs)?.let { Log.i(TAG, "$host -> $it via ${iface.name}"); return it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolve $host failed", e)
        } finally {
            runCatching { lock?.release() }
        }
        return null
    }

    private fun ask(iface: NetworkInterface, group: InetAddress, host: String, timeoutMs: Int): InetAddress? {
        // Prefer port 5353 (responders answer by multicast, which we then hear);
        // if something already owns it, a random port asks for a unicast reply
        // (RFC 6762 §6.7 "legacy unicast").
        val socket: DatagramSocket = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(5353))
                networkInterface = iface
                joinGroup(InetSocketAddress(group, 5353), iface)
            }
        } catch (e: Exception) {
            MulticastSocket(0).apply { networkInterface = iface }
        }
        socket.use { s ->
            s.soTimeout = 250
            val deadline = System.currentTimeMillis() + timeoutMs
            val unicast = s.localPort != 5353
            val queries = listOf(query(host, 1, unicast), query(host, 28, unicast))   // A, AAAA
            var nextSend = 0L
            val buf = ByteArray(9000)
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (q in queries) runCatching { s.send(DatagramPacket(q, q.size, group, 5353)) }
                    nextSend = System.currentTimeMillis() + 700            // re-ask: multicast is lossy
                }
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                parseAnswer(buf.copyOf(p.length), host)?.let { return it }
            }
        }
        return null
    }

    /** Wi-Fi / Ethernet (and hotspot) interfaces, best first. */
    private fun lanInterfaces(context: Context): List<NetworkInterface> {
        val names = LinkedHashSet<String>()
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    cm.getLinkProperties(n)?.interfaceName?.let { names += it }
                }
            }
        }
        val all = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
        // Hotspot interfaces (ap0, swlan0, wlan1…) aren't "networks" to Android; match by name.
        val lanLike = all.filter { i -> listOf("wlan", "swlan", "ap", "eth", "wifi").any { i.name.startsWith(it) } }
        return (names.mapNotNull { n -> all.find { it.name == n } } + lanLike).distinct()
    }

    /* ------------------------------------------------- DNS wire format */

    fun query(host: String, type: Int, unicastResponse: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))   // id 0, flags 0, 1 question
        for (label in host.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(type shr 8); out.write(type and 0xff)
        val cls = 1 or (if (unicastResponse) 0x8000 else 0)              // IN, QU bit for unicast
        out.write(cls shr 8); out.write(cls and 0xff)
        return out.toByteArray()
    }

    /** The A/AAAA record for [host] in a response, or null. Never throws. */
    fun parseAnswer(msg: ByteArray, host: String): InetAddress? = try {
        fun u16(i: Int) = ((msg[i].toInt() and 0xff) shl 8) or (msg[i + 1].toInt() and 0xff)
        if (msg.size < 12 || (u16(2) and 0x8000) == 0) null else {
            var pos = 12
            repeat(u16(4)) { pos = readName(msg, pos).second + 4 }
            val records = u16(6) + u16(8) + u16(10)
            var found: InetAddress? = null
            for (r in 0 until records) {
                val (name, after) = readName(msg, pos)
                val type = u16(after)
                val len = u16(after + 8)
                val data = after + 10
                if (found == null && name.equals(host.trimEnd('.'), ignoreCase = true) &&
                    ((type == 1 && len == 4) || (type == 28 && len == 16))) {
                    found = InetAddress.getByAddress(msg.copyOfRange(data, data + len))
                }
                pos = data + len
            }
            found
        }
    } catch (e: Exception) { null }

    /** A (possibly compressed) DNS name starting at [start]: the name and the offset after it. */
    private fun readName(msg: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var pos = start
        var end = -1
        var hops = 0
        while (true) {
            val len = msg[pos].toInt() and 0xff
            when {
                len == 0 -> { if (end < 0) end = pos + 1; break }
                len and 0xc0 == 0xc0 -> {
                    if (end < 0) end = pos + 2
                    pos = ((len and 0x3f) shl 8) or (msg[pos + 1].toInt() and 0xff)
                    if (++hops > 20) error("pointer loop")
                }
                else -> { labels += String(msg, pos + 1, len, Charsets.UTF_8); pos += len + 1 }
            }
        }
        return labels.joinToString(".") to end
    }
}
