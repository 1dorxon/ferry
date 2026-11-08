package com.dorxon.browserdrop.rtc

import android.content.Context
import android.util.Log
import com.dorxon.browserdrop.net.Mdns
import com.dorxon.browserdrop.proto.DataLink
import com.dorxon.browserdrop.proto.FileSource
import com.dorxon.browserdrop.proto.Host
import com.dorxon.browserdrop.proto.Proto
import com.dorxon.browserdrop.proto.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.AddIceObserver
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One peer connection and its data channel: the Android twin of the Conn class
 * in the web client. Negotiation follows the same "perfect negotiation" roles
 * (polite = our id sorts after theirs), so either side can start a transfer.
 *
 * Same network only, like the web client: no STUN, no TURN, and no candidates
 * from metered (mobile data) interfaces, so a connection forms only when both
 * devices can reach each other on the local network.
 *
 * Every method runs on the engine thread ([scope]); WebRTC callbacks arrive on
 * its own threads and are posted there in order.
 */
class RtcLink(
    private val context: Context,
    val peerId: String,
    private val polite: Boolean,
    factory: PeerConnectionFactory,
    private val scope: CoroutineScope,
    private val signal: (JSONObject) -> Unit,
    host: Host,
    private val onChange: () -> Unit,
) {
    val session = Session(peerId, scope, host)
    var state: PeerConnection.PeerConnectionState = PeerConnection.PeerConnectionState.NEW
        private set
    var closed = false
        private set

    private var dc: DataChannel? = null
    private val channels = mutableListOf<DataChannel>()   // every channel ever bound, for cleanup
    private var remoteMax: Int? = null
    private var makingOffer = false
    private val negotiation = Mutex()
    private var watchdog: Job? = null
    @Volatile private var drained: CompletableDeferred<Unit>? = null

    // Diagnostics, shown in the row when a connection can't be formed.
    @Volatile private var localCands = 0
    private var remoteCands = 0
    private var mdnsAsked = 0
    private val mdnsNames = HashSet<String>()           // each .local name is looked up once
    private val mdnsJobs = ArrayList<Job>()
    @Volatile private var mdnsFound = 0
    private fun detail() = " [${pc.iceConnectionState().name.lowercase()} · here $localCands · there $remoteCands" +
        (if (mdnsAsked > 0) " · .local $mdnsFound/$mdnsAsked" else "") + "]"

    private val pc: PeerConnection = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            // ALL, not LOW_COST: LOW_COST drops any network whose cost WebRTC can't
            // classify, which on some phones is the Wi-Fi itself. Mobile data is
            // filtered out per candidate below instead.
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        },
        object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                // Same network only: never offer a mobile-data address.
                if (c.adapterType?.name?.startsWith("CELLULAR") == true) return
                localCands++
                signal(JSONObject().put("candidate", JSONObject()
                    .put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)))
            }
            override fun onRenegotiationNeeded() { scope.launch { offer() } }
            override fun onDataChannel(ch: DataChannel) { scope.launch { bind(ch) } }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                scope.launch {
                    state = s
                    if (s == PeerConnection.PeerConnectionState.FAILED || s == PeerConnection.PeerConnectionState.CLOSED) {
                        if (!closed) {
                            session.fallback("Direct connection failed", detail(), { retry(it) }, { retryText(it) })
                            session.failAll("Direct connection failed")
                        }
                    }
                    onChange()
                }
            }
            override fun onSelectedCandidatePairChanged(e: CandidatePairChangeEvent) {
                // The far end turned out to be a public address (learnt from its
                // checks, not from anything it signalled): not this network.
                val far = e.remote?.sdp?.split(' ')?.getOrNull(4)
                if (far != null && !localAddress(far)) {
                    Log.w(TAG, "closing a connection to a non-local address")
                    scope.launch { close("That device is not on this network") }
                    return
                }
                Log.i(TAG, "path ${e.local?.sdp?.split(' ')?.getOrNull(7)}/${e.remote?.sdp?.split(' ')?.getOrNull(7)} rtt-ish lastDataMs=${e.lastDataReceivedMs}")
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
        },
    ) ?: error("WebRTC is unavailable on this device")

    /** The data channel the protocol talks through. */
    private val link = object : DataLink {
        override val isOpen get() = dc?.state() == DataChannel.State.OPEN
        override val bufferedAmount get() = dc?.bufferedAmount() ?: 0L
        override val maxMessageSize get() = remoteMax
        override fun sendText(text: String) =
            dc?.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false)) ?: false
        override fun sendBinary(buf: ByteBuffer) = dc?.send(DataChannel.Buffer(buf, true)) ?: false
        override suspend fun awaitBuffered(atMost: Long) {
            while (isOpen && bufferedAmount > atMost) {
                val d = CompletableDeferred<Unit>().also { drained = it }
                withTimeoutOrNull(250) { d.await() }     // poll as a backstop to the callback
            }
        }
    }

    val isOpen get() = link.isOpen

    /** We're sending: open the channel ourselves, which triggers negotiation. */
    fun createChannel() {
        if (dc == null) bind(pc.createDataChannel(Proto.LABEL, DataChannel.Init().apply { ordered = true }))
    }

    /**
     * If both sides open a channel at once there are two. Like the web client,
     * send on the newest but keep listening on all: the far side may be sending
     * on the other one.
     */
    private fun bind(ch: DataChannel) {
        dc = ch
        channels += ch
        session.link = link
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {
                if (ch.bufferedAmount() <= Proto.LOW_WATER) drained?.complete(Unit)
            }
            override fun onStateChange() {
                val st = ch.state()
                scope.launch {
                    when (st) {
                        DataChannel.State.OPEN -> { clearWatchdog(); session.onOpen() }
                        DataChannel.State.CLOSED -> if (!closed) session.failAll("Connection closed")
                        else -> {}
                    }
                    drained?.complete(Unit)
                    onChange()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                // The buffer is only valid during this call: copy it out first.
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                if (buffer.binary) scope.launch { session.onBinary(bytes) }
                else { val text = String(bytes, Charsets.UTF_8); scope.launch { session.onText(text) } }
            }
        })
    }

    /* --------------------------------------------------------- negotiation */

    private suspend fun offer() = negotiation.withLock {
        if (closed) return@withLock
        try {
            makingOffer = true
            pc.awaitSetLocal()
            pc.localDescription?.let { signal(describe(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "offer failed", e)
        } finally {
            makingOffer = false
        }
    }

    suspend fun handleSignal(data: JSONObject) {
        if (closed) return
        data.optJSONObject("description")?.let { d ->
            negotiation.withLock {
                val type = d.optString("type")
                val sdp = d.optString("sdp")
                val isOffer = type == "offer"
                val collision = isOffer && (makingOffer || pc.signalingState() != PeerConnection.SignalingState.STABLE)
                if (collision && !polite) return@withLock          // impolite side ignores the glare
                try {
                    if (collision) pc.awaitSetLocal(SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                    pc.awaitSetRemote(SessionDescription(SessionDescription.Type.fromCanonicalForm(type), localOnlySdp(sdp)))
                    remoteMax = Proto.maxMessageSizeFromSdp(sdp)
                    if (isOffer) {
                        pc.awaitSetLocal()
                        pc.localDescription?.let { signal(describe(it)) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "signal error", e)
                }
            }
        }
        data.optJSONObject("candidate")?.let { c ->
            val cand = c.optString("candidate")
            if (cand.isBlank()) return@let                       // end-of-candidates marker
            if (!localCandidate(cand)) return@let               // not on this network (see localCandidate)
            remoteCands++
            val mid = c.optString("sdpMid", "0")
            val index = c.optInt("sdpMLineIndex", 0)
            addCandidate(mid, index, cand)
            // A browser hides its address behind "<uuid>.local". Resolve it here
            // and add the real address too, so this side can start checks.
            val parts = cand.split(' ')
            val addr = parts.getOrNull(4)
            // Each lookup blocks a thread for seconds, and a peer chooses how many
            // names to send: look each up once, a few per link, a few at a time
            // overall, and stop with the link.
            if (addr != null && addr.endsWith(".local", ignoreCase = true) && mdnsNames.size < MDNS_PER_LINK && mdnsNames.add(addr.lowercase())) {
                mdnsAsked++
                mdnsJobs += scope.launch(Dispatchers.IO) {
                    val ip = mdnsSlots.withPermit { if (closed) null else Mdns.resolve(context, addr) } ?: return@launch
                    mdnsFound++
                    val resolved = parts.toMutableList().also { it[4] = ip.hostAddress!!.substringBefore('%') }.joinToString(" ")
                    if (localCandidate(resolved)) scope.launch { if (!closed) addCandidate(mid, index, resolved) }
                }
            }
        }
    }

    /** The observer form joins WebRTC's operations chain, so it waits for a
     *  remote description still being applied instead of failing. */
    private fun addCandidate(mid: String, index: Int, cand: String) =
        pc.addIceCandidate(IceCandidate(mid, index, cand), object : AddIceObserver {
            override fun onAddSuccess() {}
            override fun onAddFailure(error: String?) { Log.d(TAG, "candidate rejected: $error") }
        })

    private fun describe(sd: SessionDescription) =
        JSONObject().put("description", JSONObject().put("type", sd.type.canonicalForm()).put("sdp", sd.description))

    /* ------------------------------------------------------------ sending */

    fun enqueue(files: List<FileSource>) {
        val connecting = !link.isOpen
        session.enqueue(files, connecting)
        createChannel()
        armWatchdog()
    }

    fun enqueueText(text: String) {
        session.enqueueText(text, connecting = !link.isOpen)
        createChannel()
        armWatchdog()
    }

    /** Give up visibly if no direct connection forms; nothing else could carry the file. */
    private fun armWatchdog() {
        if (watchdog != null || link.isOpen) return
        val deadline = System.currentTimeMillis() + Proto.CONNECT_DEADLINE_MS
        watchdog = scope.launch {
            while (!link.isOpen) {
                delay(1000)
                if (link.isOpen) break
                val ice = pc.iceConnectionState().name.lowercase()
                session.noteQueued("Connecting… ($ice)")
                val failed = state == PeerConnection.PeerConnectionState.FAILED
                if (failed || System.currentTimeMillis() > deadline) {
                    session.fallback(if (failed) "Direct connection failed" else "Direct connection timed out", detail(), { retry(it) }, { retryText(it) })
                    break
                }
            }
            watchdog = null
        }
    }

    private fun clearWatchdog() { watchdog?.cancel(); watchdog = null }

    /** Set by the engine: resend a file (or text) over a fresh connection. */
    var retry: (FileSource) -> Unit = {}
    var retryText: (String) -> Unit = {}

    fun close(reason: String = "Peer left") {
        if (closed) return
        closed = true
        clearWatchdog()
        mdnsJobs.forEach { it.cancel() }
        session.failAll(reason)
        channels.forEach { runCatching { it.unregisterObserver(); it.close(); it.dispose() } }
        channels.clear()
        dc = null
        runCatching { pc.close(); pc.dispose() }
    }

    companion object {
        private const val TAG = "bd.rtc"
        private const val MDNS_PER_LINK = 8
        /** mDNS lookups in flight across all links, so they can't crowd out file I/O. */
        private val mdnsSlots = Semaphore(2)

        /**
         * Same network only, enforced on what the other side claims too: we never
         * advertise a public address (no STUN/TURN), and we refuse to connect to one.
         * Remote candidates must be host candidates on a private, link-local or
         * .local address (IPv6 can't be told apart from a LAN address, so it is allowed).
         * The same rule as the website's app.js.
         */
        fun localCandidate(line: String): Boolean {
            val f = line.removePrefix("a=").split(' ')
            return f.size >= 8 && f[0].startsWith("candidate:") && f[7] == "host" && localAddress(f[4])
        }

        fun localAddress(addr: String): Boolean {
            val a = addr.lowercase()
            if (a.endsWith(".local") || ':' in a) return true
            val m = Regex("^(\\d+)\\.(\\d+)\\.\\d+\\.\\d+$").find(a) ?: return false
            val x = m.groupValues[1].toInt(); val y = m.groupValues[2].toInt()
            return x == 10 || (x == 172 && y in 16..31) || (x == 192 && y == 168) || (x == 169 && y == 254) || x == 127
        }

        fun localOnlySdp(sdp: String): String =
            sdp.split("\r\n").filter { !it.startsWith("a=candidate:") || localCandidate(it) }.joinToString("\r\n")
    }
}

/* ------------------------------------------------- SdpObserver as suspend */

private suspend fun PeerConnection.awaitSetLocal(desc: SessionDescription? = null) =
    suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }
        if (desc == null) setLocalDescription(obs) else setLocalDescription(obs, desc)
    }

private suspend fun PeerConnection.awaitSetRemote(desc: SessionDescription) =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, desc)
    }
