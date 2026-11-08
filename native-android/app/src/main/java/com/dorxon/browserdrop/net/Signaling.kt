package com.dorxon.browserdrop.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The WebSocket to the signalling server (same one the website uses). It only
 * introduces devices and relays connection setup; file bytes never touch it.
 *
 * - The token makes the server derive the same peer id on every reconnect, so a
 *   network blip doesn't make this phone a stranger mid-transfer.
 * - The server groups devices by public IP. When Android switches networks
 *   (mobile data -> Wi-Fi) the old socket can live on over the old link and keep
 *   us in the wrong room, so a change of default network forces a reconnect.
 * - Keep-alive is the exact text the server's edge answers without waking it.
 */
class Signaling(
    context: Context,
    private val server: String,
    private val identity: () -> Pair<String, String>,   // name, token
    private val listener: Listener,
    /** A 6-digit pairing code: join that code's private room instead of the network's. */
    private val pair: String? = null,
) {
    interface Listener {
        fun onWelcome(id: String, room: String?, peers: List<PeerInfo>)
        fun onPeers(peers: List<PeerInfo>)
        fun onSignal(from: String, data: JSONObject)
        fun onConnected(up: Boolean)
    }

    data class PeerInfo(val id: String, val name: String, val device: String)

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "bd-signal").apply { isDaemon = true } }
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    private var retry = 0
    private var retryTask: ScheduledFuture<*>? = null
    private var pingTask: ScheduledFuture<*>? = null
    private var currentNetwork: Network? = null

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            timer.execute {
                if (currentNetwork != null && network != currentNetwork) {
                    Log.i(TAG, "default network changed; rejoining")
                    reconnectNow()
                }
                currentNetwork = network
            }
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            timer.execute { if (currentNetwork == null) currentNetwork = network }
        }
    }

    fun start() = timer.execute {
        if (running) return@execute
        running = true
        runCatching { cm.registerDefaultNetworkCallback(netCallback) }
        open()
        pingTask = timer.scheduleWithFixedDelay({ ws?.send(PING) }, 50, 50, TimeUnit.SECONDS)
    }

    fun stop() = timer.execute {
        if (!running) return@execute
        running = false
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        pingTask?.cancel(false); retryTask?.cancel(false)
        ws?.close(1000, "bye"); ws = null
        listener.onConnected(false)
    }

    /** Stop for good and release the thread (a pairing socket that's finished). */
    fun close() {
        stop()
        timer.execute { timer.shutdown() }
    }

    fun send(obj: JSONObject): Boolean = ws?.send(obj.toString()) ?: false

    fun signal(to: String, data: JSONObject) =
        send(JSONObject().put("type", "signal").put("to", to).put("data", data))

    fun rename(name: String) = send(JSONObject().put("type", "rename").put("name", name))

    /** Drop the socket and open a fresh one now (e.g. the network changed). */
    fun reconnectNow() = timer.execute {
        if (!running) return@execute
        retry = 0
        retryTask?.cancel(false)
        val old = ws
        ws = null                       // its callbacks are now ignored
        old?.cancel()
        listener.onConnected(false)
        open()
    }

    private fun open() {
        val (name, token) = identity()
        val base = server.toHttpUrl()
        val url = base.newBuilder()
            .scheme(if (base.isHttps) "https" else "http")   // OkHttp upgrades http(s) to ws(s)
            .encodedPath("/ws")
            .addQueryParameter("name", name)
            .addQueryParameter("device", "android")
            .addQueryParameter("token", token)
            .apply { pair?.let { addQueryParameter("pair", it) } }
            .build()
        val req = Request.Builder().url(url).build()
        lateinit var socket: WebSocket
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                timer.execute { if (ws === socket) { retry = 0; listener.onConnected(true) } }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ws !== socket) return
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "welcome" -> listener.onWelcome(msg.getString("id"), msg.optString("room").ifBlank { null }, peersOf(msg))
                    "peers" -> listener.onPeers(peersOf(msg))
                    "signal" -> msg.optJSONObject("data")?.let { listener.onSignal(msg.optString("from"), it) }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(socket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(socket)
        })
        ws = socket
    }

    /** Back off, but keep trying: phones lose the socket on every sleep and network blip. */
    private fun dropped(socket: WebSocket) = timer.execute {
        if (ws !== socket || !running) return@execute
        ws = null
        listener.onConnected(false)
        retry = minOf(retry + 1, 6)
        retryTask = timer.schedule({ if (running && ws == null) open() }, 500L shl retry, TimeUnit.MILLISECONDS)
    }

    private fun peersOf(msg: JSONObject): List<PeerInfo> {
        val arr = msg.optJSONArray("peers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            PeerInfo(p.optString("id"), p.optString("name", "Device"), p.optString("device", "desktop"))
        }.filter { it.id.isNotBlank() }
    }

    companion object {
        private const val TAG = "bd.signal"
        const val PING = "{\"type\":\"ping\"}"
    }
}
