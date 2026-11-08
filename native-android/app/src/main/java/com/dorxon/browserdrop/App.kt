package com.dorxon.browserdrop

import android.app.Application
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.dorxon.browserdrop.service.Notifications
import com.dorxon.browserdrop.service.TransferService

class App : Application() {
    lateinit var engine: Engine
        private set

    /** Whether any of our screens is visible. Requests and results notify only when not. */
    @Volatile var foreground = false
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        engine = Engine(this)
        engine.listener = object : Engine.Listener {
            override fun onAsk(key: String, from: String, name: String, size: Long, nonce: String) {
                if (!foreground) Notifications.ask(this@App, key, from, name, size, nonce)
            }
            override fun onAskGone(key: String) = Notifications.cancelAsk(this@App, key)
            override fun onReceived(key: String, name: String, uri: Uri, mime: String?) {
                if (!foreground) Notifications.received(this@App, key, name, uri, mime)
            }
            override fun onText(key: String, from: String, text: String) {
                if (!foreground) Notifications.text(this@App, key, from, text)
            }
            override fun onBusy(busy: Boolean) { if (busy) TransferService.start(this@App) }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground = true; engine.start() }
            override fun onStop(owner: LifecycleOwner) { foreground = false; engine.backgrounded() }
        })
    }
}
