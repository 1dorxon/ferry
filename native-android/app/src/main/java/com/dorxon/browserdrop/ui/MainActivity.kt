package com.dorxon.browserdrop.ui

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.graphics.Color as AColor
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dorxon.browserdrop.App
import com.dorxon.browserdrop.Engine
import com.dorxon.browserdrop.TransferUi
import com.dorxon.browserdrop.service.Clip
import com.dorxon.browserdrop.service.Notifications

class MainActivity : ComponentActivity() {
    private val engine: Engine get() = (application as App).engine
    private var pickFor: String? = null

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val peer = pickFor ?: return@registerForActivityResult
        pickFor = null
        if (uris.isNotEmpty()) engine.sendUris(peer, uris)
    }

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pickFor = savedInstanceState?.getString("pickFor")
        handle(intent)
        maybeAskNotifications()
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        setContent {
            var theme by remember {
                mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "AUTO") }.getOrDefault(ThemeMode.AUTO))
            }
            val dark = theme.isDark()
            // Status and navigation bar icons follow the app's theme, not just the system's.
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT) else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            FerryTheme(theme) {
                val s by engine.state.collectAsStateWithLifecycle()
                Screen(s, theme = theme, a = Actions(
                    tapPeer = { peer ->
                        if (s.shared.isNotEmpty()) engine.sendShared(peer.id)
                        else { pickFor = peer.id; pick.launch(arrayOf("*/*")) }
                    },
                    rename = engine::rename,
                    answer = { key, yes -> engine.answer(key, yes) },
                    cancel = engine::cancel,
                    retry = engine::retry,
                    open = ::open,
                    clearFinished = { engine.clearFinished() },
                    clearShared = { engine.clearShared() },
                    openDownloads = ::openDownloads,
                    pair = { code -> engine.startPair(code) },
                    stopPair = { engine.stopPair() },
                    dismissPairPrompt = { engine.dismissPairPrompt() },
                    dismissSpeedTip = { engine.dismissSpeedTip() },
                    setTheme = { m -> theme = m; prefs.edit().putString("theme", m.name).apply() },
                    sendText = { peerId, text -> engine.sendText(peerId, text) },
                    copy = { t -> t.text?.let { Clip.copy(this, it) } },
                    readClipboard = { Clip.read(this) },
                    clearSharedText = { engine.setSharedText(null) },
                ))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pickFor", pickFor)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Share sheet, and "Accept" from a notification. */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            // A file, or text / a link (Chrome's "Share" sends the page address as text).
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { engine.setShared(listOf(it)) }
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { engine.setSharedText(it) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { engine.setShared(it) }
            // Only counts with the prompt's nonce, which only our own notification has.
            Notifications.ACTION_ACCEPT -> intent.getStringExtra(Notifications.EXTRA_KEY)?.let {
                engine.answerFromNotification(it, intent.getStringExtra(Notifications.EXTRA_NONCE))
            }
        }
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun open(t: TransferUi) {
        val uri = t.location ?: return
        // The type of the file as saved, never the sender's claim.
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: t.mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(view, t.name)) }
        catch (e: ActivityNotFoundException) { Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show() }
    }

    private fun openDownloads() {
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        catch (e: ActivityNotFoundException) { Toast.makeText(this, "Files are in Downloads › Ferry", Toast.LENGTH_LONG).show() }
    }
}
