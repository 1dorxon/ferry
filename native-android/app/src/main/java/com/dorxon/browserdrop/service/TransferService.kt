package com.dorxon.browserdrop.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dorxon.browserdrop.App
import com.dorxon.browserdrop.Engine
import com.dorxon.browserdrop.Phase
import com.dorxon.browserdrop.R
import com.dorxon.browserdrop.UiState
import com.dorxon.browserdrop.proto.Dir
import com.dorxon.browserdrop.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps transfers alive when the app isn't on screen -- the thing a web page
 * can't do. Runs only while bytes are moving: a progress notification, a CPU
 * wake lock (so the screen turning off doesn't stall the transfer) and a Wi-Fi
 * lock (so the radio doesn't drop into power-save and throttle it).
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var watcher: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as App).engine
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, build(engine.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        holdLocks()
        watcher?.cancel()                          // started again while running: one watcher only
        watcher = scope.launch {
            engine.state.collectLatest { s ->
                if (s.active.isEmpty()) {
                    delay(1500)                     // a queue's next file starts within moments
                    stop()
                } else {
                    runCatching { NotificationManagerCompat.from(this@TransferService).notify(Notifications.ID_SERVICE, build(s)) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(s: UiState): Notification {
        val moving = s.active
        val total = moving.sumOf { it.size }.coerceAtLeast(1)
        val done = moving.sumOf { it.done }
        val rate = moving.sumOf { it.rate }
        val one = moving.singleOrNull()
        val title = when {
            one == null -> if (moving.isEmpty()) "Finishing…" else "${moving.size} transfers"
            one.dir == Dir.OUT -> "Sending ${one.name}"
            else -> "Receiving ${one.name}"
        }
        val pct = (done * 100 / total).toInt()
        val left = if (rate > 0) Engine.fmtEta((total - done) / rate) + " left" else ""
        val text = listOf(
            one?.let { if (it.dir == Dir.OUT) "to ${it.peerName}" else "from ${it.peerName}" },
            if (moving.any { it.phase == Phase.ACTIVE }) "$pct%" else "Connecting…",
            if (rate > 0) Engine.fmtRate(rate) else null,
            left.ifBlank { null },
        ).filterNotNull().joinToString(" · ")
        return NotificationCompat.Builder(this, Notifications.CH_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, pct, moving.none { it.phase == Phase.ACTIVE })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun holdLocks() {
        if (cpu == null) cpu = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ferry:transfer").apply { acquire(6 * 60 * 60_000L) }
        if (wifi == null) wifi = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Ferry:transfer").apply { acquire() }
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15 caps data-sync services at 6 h a day; bow out cleanly if we hit it. */
    override fun onTimeout(startId: Int, fgsType: Int) { stop() }

    override fun onDestroy() {
        scope.cancel()
        runCatching { cpu?.release() }; cpu = null
        runCatching { wifi?.release() }; wifi = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            // Starting from the background is restricted; when that's refused the
            // transfer still runs, just without the guarantee.
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java)) }
                .onFailure { Log.w("bd.service", "couldn't start foreground service", it) }
        }
    }
}
