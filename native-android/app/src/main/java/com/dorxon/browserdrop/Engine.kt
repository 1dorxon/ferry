package com.dorxon.browserdrop

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.dorxon.browserdrop.io.DownloadSink
import com.dorxon.browserdrop.io.UriSource
import com.dorxon.browserdrop.net.Signaling
import com.dorxon.browserdrop.net.WifiBand
import com.dorxon.browserdrop.proto.Dir
import com.dorxon.browserdrop.proto.FileSink
import com.dorxon.browserdrop.proto.FileSource
import com.dorxon.browserdrop.proto.Host
import com.dorxon.browserdrop.proto.Transfer
import com.dorxon.browserdrop.rtc.RtcLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.security.SecureRandom
import java.util.concurrent.Executors

/* ------------------------------------------------------------- UI state */

enum class Phase { PENDING, ASKING, ACTIVE, DONE, FAILED }

data class PeerUi(val id: String, val name: String, val device: String, val progress: Float?, val paired: Boolean = false)

data class TransferUi(
    val key: String,
    val dir: Dir,
    val name: String,
    val size: Long,
    val peerName: String,
    val phase: Phase,
    val done: Long,
    val rate: Double,
    val note: String,
    val location: Uri?,
    val mime: String?,
    val canRetry: Boolean,
    /** Text (the clipboard) rather than a file. */
    val text: String? = null,
)

data class UiState(
    val name: String = "",
    val connected: Boolean = false,
    val joined: Boolean = false,
    val peers: List<PeerUi> = emptyList(),
    val transfers: List<TransferUi> = emptyList(),
    val shared: List<String> = emptyList(),
    /** The active pairing code, if any (see "Pairing" in Engine). */
    val pairCode: String? = null,
    val pairHosting: Boolean = false,
    /** Devices currently in the pairing room. */
    val pairFound: List<String> = emptyList(),
    /** Nobody around for a while: offer a code. */
    val pairPrompt: Boolean = false,
    /** On 2.4 GHz Wi-Fi after a transfer: suggest the router's 5 GHz network. */
    val speedTip: Boolean = false,
    /** Text shared in from another app, waiting for a device. */
    val sharedText: String? = null,
) {
    val active get() = transfers.filter { it.phase == Phase.ACTIVE || it.phase == Phase.PENDING }
}

/**
 * Everything that isn't drawing: the signalling socket, a WebRTC link per peer,
 * transfer state, storage, and what the UI and notifications show.
 *
 * All state lives on one thread ([scope]); callbacks from OkHttp and WebRTC are
 * posted to it. The UI only reads [state] and calls the public actions.
 */
class Engine(private val app: Context) : Host, Signaling.Listener {

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "bd-engine") }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + thread)
    private val prefs = app.getSharedPreferences("bd", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState(name = myName()))
    val state: StateFlow<UiState> = _state

    private var myId: String? = null
    private var room: String? = null
    private var connected = false
    private val peers = LinkedHashMap<String, Signaling.PeerInfo>()      // everyone shown: both rooms
    private val autoPeers = LinkedHashMap<String, Signaling.PeerInfo>()  // this network's room
    private val pairPeers = LinkedHashMap<String, Signaling.PeerInfo>()  // the pairing code's room
    private val links = HashMap<String, RtcLink>()

    private class Row(val t: Transfer, var peerName: String) {
        var phase = Phase.PENDING
        var done = 0L
        var rate = 0.0
        var note = ""
        var location: Uri? = null
        var mime: String? = null
        var retry: (() -> Unit)? = null
        var answer: CompletableDeferred<Boolean>? = null
        var nonce: String? = null  // proves an Accept came from our own notification
        var started = 0L          // when bytes started moving, for "in 16.1s"
    }
    private val rows = LinkedHashMap<String, Row>()
    private var rowSeq = 0
    private var shared: List<UriSource> = emptyList()
    private var sharedText: String? = null

    var listener: Listener? = null
    interface Listener {
        /** An incoming file needs an answer (app may be in the background). */
        fun onAsk(key: String, from: String, name: String, size: Long, nonce: String)
        fun onAskGone(key: String)
        fun onReceived(key: String, name: String, uri: Uri, mime: String?)
        /** Text arrived (app may be in the background). */
        fun onText(key: String, from: String, text: String) {}
        /** Transfers are moving (or stopped moving): keep the process alive. */
        fun onBusy(busy: Boolean)
    }

    private val signaling = Signaling(app, BuildConfig.SERVER, { myName() to token() }, this)

    /* ------------------------------------------------------------ pairing
     * Rooms come from the public address a device connects from, and some
     * networks make two devices on one Wi-Fi look like different networks (one
     * on IPv4 and one on IPv6 -- Android can't use stateful DHCPv6 --, a VPN, a
     * mesh router). A shared 6-digit code puts both in a private room as well,
     * over a second socket. Same protocol as the website, so an app and a
     * browser can pair. Transfers stay same-network only. */
    private var pairSignal: Signaling? = null
    private var pairCode: String? = null
    private var pairHosting = false
    private var pairStarted = 0L
    private var pairMet = false
    private var lonelySince = System.currentTimeMillis()
    private var promptDismissed = false
    private var promptShown = false
    private var on24 = false
    private var speedTipShown = false

    /** "Got it" on the 5 GHz tip: quiet for a week. */
    fun dismissSpeedTip() = scope.launch {
        prefs.edit().putLong("speedTipAt", System.currentTimeMillis()).apply()
        emit()
    }

    private fun wantsSpeedTip() = on24 && rows.values.any { it.t.text == null && (it.phase == Phase.DONE || it.phase == Phase.ACTIVE) } &&
        System.currentTimeMillis() - prefs.getLong("speedTipAt", 0) > 7 * 86_400_000L

    private val pairListener = object : Signaling.Listener {
        override fun onWelcome(id: String, room: String?, peers: List<Signaling.PeerInfo>) {
            scope.launch { if (myId == null) myId = id; setPeersFrom(pairPeers, peers) }
        }
        override fun onPeers(peers: List<Signaling.PeerInfo>) { scope.launch { setPeersFrom(pairPeers, peers) } }
        override fun onSignal(from: String, data: JSONObject) { scope.launch { linkFor(from, sending = false)?.handleSignal(data) } }
        override fun onConnected(up: Boolean) {}
    }

    /** Show a new code (hosting), or join [code] from another device. */
    fun startPair(code: String? = null) = scope.launch {
        stopPairing()
        val c = code ?: "%06d".format(SecureRandom().nextInt(1_000_000))
        pairCode = c
        pairHosting = code == null
        pairStarted = System.currentTimeMillis()
        pairMet = false
        pairSignal = Signaling(app, BuildConfig.SERVER, { myName() to token() }, pairListener, pair = c).also { it.start() }
        emit()
    }

    fun stopPair() = scope.launch { stopPairing(); emit() }

    fun dismissPairPrompt() = scope.launch { promptDismissed = true; emit() }

    private fun stopPairing() {
        pairSignal?.close()
        pairSignal = null
        pairCode = null
        if (pairPeers.isNotEmpty()) setPeersFrom(pairPeers, emptyList())
    }

    init {
        // The prompt appears after a quiet spell; unused codes expire.
        scope.launch {
            while (true) {
                delay(2000)
                if (pairCode != null && !pairMet && System.currentTimeMillis() - pairStarted > PAIR_TTL_MS) { stopPairing(); emit() }
                on24 = WifiBand.on24GHz(app)
                if (wantsPrompt() != promptShown || wantsSpeedTip() != speedTipShown) emit()
            }
        }
    }

    private fun wantsPrompt() = peers.isEmpty() && pairCode == null && !promptDismissed && connected &&
        System.currentTimeMillis() - lonelySince > 12_000

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    /* ------------------------------------------------------------ identity */

    /** A browser can't read the phone's name; an app can (Settings → About → Device name). */
    fun myName(): String = prefs.getString("name", null) ?: defaultName()

    private fun defaultName(): String =
        runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
                .distinct().joinToString(" ").let { if (Build.MODEL.startsWith(Build.MANUFACTURER, true)) Build.MODEL else it }

    /** Private token; the server derives our public id from it, stable across reconnects. */
    private fun token(): String = prefs.getString("token", null) ?: ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
        .also { prefs.edit().putString("token", it).apply() }

    fun rename(name: String) = scope.launch {
        val v = name.replace(Regex("\\s+"), " ").trim().take(32)
        if (v.isEmpty()) return@launch
        prefs.edit().putString("name", v).apply()
        signaling.rename(v)
        pairSignal?.rename(v)
        emit()
    }

    /* --------------------------------------------------------- lifecycle */

    private var stopJob: Job? = null

    /** App came to the foreground (or a transfer needs us): be visible. */
    fun start() = scope.launch { stopJob?.cancel(); stopJob = null; signaling.start(); pairSignal?.start() }

    /**
     * App went to the background. Stay reachable for a while -- requests arrive
     * as notifications -- then disconnect, unless something is still moving.
     */
    fun backgrounded() = scope.launch {
        stopJob?.cancel()
        stopJob = scope.launch {
            while (true) {
                delay(10 * 60_000L)
                if (links.values.none { it.session.busy }) { signaling.stop(); pairSignal?.stop(); break }
            }
        }
    }

    /* ---------------------------------------------------- signalling events */

    override fun onWelcome(id: String, room: String?, peers: List<Signaling.PeerInfo>) {
        scope.launch {
            // A different id means the server forgot us (new token): old links are useless.
            if (myId != null && myId != id) { links.values.forEach { it.close("Reconnected") }; links.clear() }
            myId = id
            this@Engine.room = room
            setPeers(peers)
        }
    }

    override fun onPeers(peers: List<Signaling.PeerInfo>) { scope.launch { setPeers(peers) } }

    private fun setPeers(list: List<Signaling.PeerInfo>) = setPeersFrom(autoPeers, list)

    override fun onConnected(up: Boolean) { scope.launch { connected = up; if (!up) room = null; emit() } }

    override fun onSignal(from: String, data: JSONObject) {
        scope.launch { linkFor(from, sending = false)?.handleSignal(data) }
    }

    private fun setPeersFrom(source: MutableMap<String, Signaling.PeerInfo>, list: List<Signaling.PeerInfo>) {
        source.clear()
        list.forEach { source[it.id] = it }
        // One radar: everyone from either room (a device can be in both).
        peers.clear()
        peers.putAll(autoPeers)
        pairPeers.forEach { (id, p) -> peers.putIfAbsent(id, p) }
        if (pairPeers.isNotEmpty()) pairMet = true
        if (peers.isNotEmpty()) lonelySince = Long.MAX_VALUE
        else if (lonelySince == Long.MAX_VALUE) lonelySince = System.currentTimeMillis()
        // A peer missing from the list is usually just reconnecting to the server;
        // a live direct connection doesn't need the server, so keep it.
        for ((id, l) in links.toList()) {
            if (id in peers) continue
            if (l.state == PeerConnection.PeerConnectionState.CONNECTED) continue
            l.close(); links.remove(id)
        }
        rows.values.forEach { r -> peers[r.t.peerId]?.let { r.peerName = labelOf(r.t.peerId) } }
        emit()
    }

    /** Relay through the room we know the peer from (the network room first). */
    private fun route(peerId: String): Signaling =
        pairSignal?.takeIf { peerId !in autoPeers && peerId in pairPeers } ?: signaling

    private fun linkFor(peerId: String, sending: Boolean): RtcLink? {
        val me = myId ?: return null
        links[peerId]?.let { l ->
            val dead = l.closed || l.state == PeerConnection.PeerConnectionState.FAILED ||
                l.state == PeerConnection.PeerConnectionState.CLOSED
            if (!dead || l.session.busy) return l
            l.close(); links.remove(peerId)
        }
        val l = RtcLink(app, peerId, polite = me > peerId, factory, scope,
            signal = { data -> route(peerId).signal(peerId, data) }, host = this, onChange = { emit() })
        l.retry = { src -> sendTo(peerId, listOf(src)) }
        l.retryText = { text -> sendText(peerId, text) }
        links[peerId] = l
        if (sending) l.createChannel()
        return l
    }

    /* --------------------------------------------------------- UI actions */

    fun sendUris(peerId: String, uris: List<Uri>) = scope.launch {
        val sources = withContext(Dispatchers.IO) { uris.mapNotNull { UriSource.from(app, it) } }
        if (sources.isNotEmpty()) sendTo(peerId, sources)
    }

    private fun sendTo(peerId: String, files: List<FileSource>) {
        scope.launch { linkFor(peerId, sending = true)?.enqueue(files) }
    }

    fun sendText(peerId: String, text: String) = scope.launch {
        if (text.isNotEmpty() && text.length <= com.dorxon.browserdrop.proto.Proto.TEXT_MAX) linkFor(peerId, sending = true)?.enqueueText(text)
    }

    /** Text from another app's Share sheet: shown in the send-text sheet. */
    fun setSharedText(text: String?) = scope.launch { sharedText = text?.takeIf { it.isNotEmpty() }; emit() }

    /** The full text of a text row, for Copy (the notification has only the key). */
    fun textOf(key: String): String? = _state.value.transfers.firstOrNull { it.key == key }?.text

    fun setShared(uris: List<Uri>) = scope.launch {
        shared = withContext(Dispatchers.IO) { uris.mapNotNull { UriSource.from(app, it) } }
        emit()
    }

    fun sendShared(peerId: String) = scope.launch {
        val files = shared
        if (files.isEmpty()) return@launch
        shared = emptyList()
        sendTo(peerId, files)
        emit()
    }

    fun clearShared() = scope.launch { shared = emptyList(); emit() }

    fun answer(key: String, accept: Boolean) = scope.launch { rows[key]?.answer?.complete(accept) }

    /**
     * Accept pressed on the notification. MainActivity is exported (the launcher
     * and share sheet need that), so any app could send it an "accept"; only one
     * carrying the prompt's random nonce counts.
     */
    fun answerFromNotification(key: String, nonce: String?) = scope.launch {
        val r = rows[key] ?: return@launch
        if (nonce != null && r.nonce != null && java.security.MessageDigest.isEqual(nonce.toByteArray(), r.nonce!!.toByteArray())) {
            r.answer?.complete(true)
        }
    }

    fun cancel(key: String) = scope.launch {
        val r = rows[key] ?: return@launch
        r.t.cancel?.invoke() ?: fail(r.t, "Cancelled")
    }

    fun retry(key: String) = scope.launch {
        val r = rows.remove(key) ?: return@launch
        r.retry?.invoke()
        emit()
    }

    fun clearFinished() = scope.launch {
        rows.values.removeAll { it.phase == Phase.DONE || it.phase == Phase.FAILED }
        emit()
    }

    fun dismiss(key: String) = scope.launch {
        val r = rows[key] ?: return@launch
        if (r.phase == Phase.DONE || r.phase == Phase.FAILED) { rows.remove(key); emit() }
    }

    /* --------------------------------------------------------------- Host */

    override fun newTransfer(dir: Dir, name: String, size: Long, peerId: String): Transfer {
        val t = Transfer("t${++rowSeq}", dir, name, size, peerId)
        rows[t.key] = Row(t, labelOf(peerId)).apply {
            note = if (dir == Dir.OUT) "${fmtBytes(size)} · to $peerName" else "${fmtBytes(size)} · from $peerName"
        }
        emit()
        return t
    }

    override fun newText(dir: Dir, text: String, peerId: String): Transfer {
        val first = text.trim().lineSequence().firstOrNull()?.take(120)?.ifBlank { null } ?: "Text"
        val t = Transfer("t${++rowSeq}", dir, first, text.length.toLong(), peerId)
        t.text = text
        rows[t.key] = Row(t, labelOf(peerId)).apply {
            note = "${fmtChars(text.length)} · ${if (dir == Dir.OUT) "to" else "from"} $peerName"
        }
        emit()
        return t
    }

    override fun note(t: Transfer, text: String) {
        rows[t.key]?.let { it.note = text; if (it.phase != Phase.ASKING) it.phase = Phase.PENDING }
        emit()
    }

    private var lastProgressEmit = 0L
    override fun progress(t: Transfer, done: Long, rate: Double) {
        val r = rows[t.key] ?: return
        val first = r.phase != Phase.ACTIVE
        if (first) r.started = System.currentTimeMillis()
        r.phase = Phase.ACTIVE
        r.done = done
        if (rate > 0) r.rate = rate
        r.note = "${(done * 100 / maxOf(1, t.size))}% · ${if (t.dir == Dir.OUT) "to" else "from"} ${r.peerName}"
        // A progress callback per 256 KB chunk is far more than a screen needs.
        val now = System.currentTimeMillis()
        if (first || now - lastProgressEmit > 120 || done >= t.size) { lastProgressEmit = now; emit() }
    }

    override fun finish(t: Transfer, verb: String, location: Any?) {
        val r = rows[t.key] ?: return
        r.phase = Phase.DONE
        r.done = t.size
        t.text?.let { text ->
            r.note = "$verb · ${fmtChars(text.length)} · ${if (t.dir == Dir.OUT) "to" else "from"} ${r.peerName}"
            emit()
            if (t.dir == Dir.IN) listener?.onText(t.key, r.peerName, text)
            return
        }
        val secs = if (r.started > 0) (System.currentTimeMillis() - r.started) / 1000.0 else 0.0
        r.note = "$verb · ${fmtBytes(t.size)}" + if (secs > 0) " in ${fmtSecs(secs)}" else ""
        r.location = location as? Uri
        if (t.dir == Dir.IN) r.location?.let { listener?.onReceived(t.key, t.name, it, r.mime) }
        emit()
    }

    override fun fail(t: Transfer, reason: String, retry: (() -> Unit)?) {
        val r = rows[t.key] ?: return
        if (r.phase == Phase.DONE) return
        if (r.phase == Phase.ASKING) listener?.onAskGone(t.key)
        r.phase = Phase.FAILED
        r.note = reason
        r.retry = retry
        r.answer = null
        emit()
    }

    override fun requestAccept(t: Transfer, answer: CompletableDeferred<Boolean>) {
        val r = rows[t.key] ?: return
        r.phase = Phase.ASKING
        r.answer = answer
        r.note = "${r.peerName} wants to send ${fmtBytes(t.size)}"
        r.nonce = java.util.UUID.randomUUID().toString()
        listener?.onAsk(t.key, r.peerName, t.name, t.size, r.nonce!!)
        answer.invokeOnCompletion {
            scope.launch {
                listener?.onAskGone(t.key)
                if (r.phase == Phase.ASKING) { r.phase = Phase.PENDING; r.note = "Starting…" }
                emit()
            }
        }
        emit()
    }

    override fun openSink(t: Transfer, name: String, size: Long, mime: String): FileSink {
        val type = DownloadSink.mimeFor(name)
        rows[t.key]?.mime = type
        return DownloadSink.create(app, name, type)
    }

    override fun peerName(peerId: String) = peers[peerId]?.let { labelOf(peerId) }

    /** A device as shown in prompts and rows: its name and a short tag from its id. */
    private fun labelOf(peerId: String) = "${peers[peerId]?.name ?: "Device"} · ${tagOf(peerId)}"

    /* -------------------------------------------------------------- emit */

    private var wasBusy = false

    private fun emit() {
        val ring = HashMap<String, Float>()
        for (r in rows.values) if (r.phase == Phase.ACTIVE) ring[r.t.peerId] = r.done.toFloat() / maxOf(1L, r.t.size)
        _state.value = UiState(
            name = myName(),
            connected = connected,
            joined = room != null,
            peers = peers.values.map { PeerUi(it.id, it.name, it.device, ring[it.id], paired = it.id !in autoPeers) },
            transfers = rows.values.reversed().map { r ->
                TransferUi(r.t.key, r.t.dir, r.t.name, r.t.size, r.peerName, r.phase, r.done, r.rate, r.note,
                    r.location, r.mime, r.retry != null, r.t.text)
            },
            shared = shared.map { it.name },
            pairCode = pairCode,
            pairHosting = pairHosting,
            pairFound = pairPeers.values.map { it.name },
            pairPrompt = wantsPrompt().also { promptShown = it },
            speedTip = wantsSpeedTip().also { speedTipShown = it },
            sharedText = sharedText,
        )
        val busy = rows.values.any { it.t.text == null && (it.phase == Phase.ACTIVE || (it.phase == Phase.PENDING && it.t.dir == Dir.OUT)) }
        if (busy != wasBusy) { wasBusy = busy; listener?.onBusy(busy) }
    }

    companion object {
        private const val TAG = "bd.engine"
        private const val PAIR_TTL_MS = 15 * 60_000L          // an unused code is dropped after this
        /** Short tag from a device's id, so two devices with similar names can be told apart. */
        fun tagOf(id: String) = id.take(4).uppercase()
        fun fmtBytes(n: Long): String {
            if (n < 1024) return "$n B"
            val u = arrayOf("KB", "MB", "GB", "TB")
            var v = n.toDouble(); var i = -1
            do { v /= 1024; i++ } while (v >= 1024 && i < u.size - 1)
            return if (v < 10) "%.1f %s".format(v, u[i]) else "${Math.round(v)} ${u[i]}"
        }
        fun fmtChars(n: Int) = if (n == 1) "1 character" else "%,d characters".format(n)
        fun fmtRate(bps: Double) = if (bps >= 1e6) "%.1f MB/s".format(bps / 1e6) else "${Math.round(bps / 1e3)} KB/s"
        fun fmtSecs(s: Double) = if (s < 60) "%.1fs".format(s) else fmtEta(s)
        fun fmtEta(s: Double): String = when {
            !s.isFinite() || s < 0 -> "—"
            s >= 3600 -> "%d:%02d:00".format((s / 3600).toInt(), ((s / 60) % 60).toInt())
            else -> "%d:%02d".format((s / 60).toInt(), Math.round(s % 60).toInt())
        }
    }
}
