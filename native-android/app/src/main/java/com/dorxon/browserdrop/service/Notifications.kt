package com.dorxon.browserdrop.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dorxon.browserdrop.App
import com.dorxon.browserdrop.Engine
import com.dorxon.browserdrop.R
import com.dorxon.browserdrop.ui.MainActivity

object Notifications {
    const val CH_TRANSFERS = "transfers"
    const val CH_REQUESTS = "requests"
    const val CH_RECEIVED = "received"
    const val CH_TEXT = "text"
    const val ID_SERVICE = 1
    const val ACTION_ACCEPT = "com.dorxon.browserdrop.ACCEPT"
    const val ACTION_DECLINE = "com.dorxon.browserdrop.DECLINE"
    const val ACTION_COPY = "com.dorxon.browserdrop.COPY"
    const val EXTRA_KEY = "key"
    const val EXTRA_NONCE = "nonce"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_REQUESTS, "Incoming files", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a nearby device wants to send you something"
            },
            NotificationChannel(CH_TRANSFERS, "Transfers in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while files are moving"
                setShowBadge(false)
            },
            NotificationChannel(CH_RECEIVED, "Received files", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_TEXT, "Received text", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Text or a link another device sent, with Copy"
            },
        ))
    }

    private fun idFor(key: String) = 1000 + (key.hashCode() and 0xffff)

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** "Studio Mac wants to send Goa trip.mov" with Decline / Accept. */
    fun ask(ctx: Context, key: String, from: String, name: String, size: Long, nonce: String) {
        val id = idFor(key)
        // Accept opens the app: that brings us to the foreground, which is what
        // lets the transfer service start and the bytes keep flowing.
        val accept = PendingIntent.getActivity(ctx, id,
            Intent(ctx, MainActivity::class.java).setAction(ACTION_ACCEPT).putExtra(EXTRA_KEY, key).putExtra(EXTRA_NONCE, nonce)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val decline = PendingIntent.getBroadcast(ctx, id,
            Intent(ctx, ActionReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$from wants to send you a file")
            .setContentText("$name · ${Engine.fmtBytes(size)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .addAction(0, "Decline", decline)
            .addAction(0, "Accept", accept)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }   // no permission: the app shows it instead
    }

    fun cancelAsk(ctx: Context, key: String) = NotificationManagerCompat.from(ctx).cancel(idFor(key))

    /** "Pixel 8 sent you text" with the text, and Copy. */
    fun text(ctx: Context, key: String, from: String, text: String) {
        val id = idFor(key) + 2
        val copy = PendingIntent.getBroadcast(ctx, id,
            Intent(ctx, ActionReceiver::class.java).setAction(ACTION_COPY).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val preview = text.take(600)
        val n = NotificationCompat.Builder(ctx, CH_TEXT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$from sent you text")
            .setContentText(preview.replace(Regex("\\s+"), " "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .addAction(0, "Copy", copy)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun cancelText(ctx: Context, key: String) = NotificationManagerCompat.from(ctx).cancel(idFor(key) + 2)

    fun received(ctx: Context, key: String, name: String, uri: Uri, mime: String?) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = NotificationCompat.Builder(ctx, CH_RECEIVED)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Received $name")
            .setContentText("Saved to Downloads/Ferry · tap to open")
            .setContentIntent(PendingIntent.getActivity(ctx, idFor(key), Intent.createChooser(view, null),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(idFor(key) + 1, n) }
    }
}

/** Decline, or Copy, from the notification without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.getStringExtra(Notifications.EXTRA_KEY) ?: return
        val engine = (ctx.applicationContext as App).engine
        when (intent.action) {
            Notifications.ACTION_DECLINE -> { engine.answer(key, false); Notifications.cancelAsk(ctx, key) }
            Notifications.ACTION_COPY -> { engine.textOf(key)?.let { Clip.copy(ctx, it) }; Notifications.cancelText(ctx, key) }
            else -> Notifications.cancelAsk(ctx, key)
        }
    }
}

/** The system clipboard. */
object Clip {
    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Ferry", text))
        // Android 13+ confirms a copy itself; before that, say so.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** What's copied on this phone. Android allows this only while we're on screen. */
    fun read(ctx: Context): String? =
        ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString()?.takeIf { it.isNotEmpty() }
}
