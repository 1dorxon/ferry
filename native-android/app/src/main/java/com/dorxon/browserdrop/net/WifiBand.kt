package com.dorxon.browserdrop.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Whether this phone is on 2.4 GHz Wi-Fi -- the usual reason transfers top out
 * around 5 MB/s, where the same router's 5 GHz network typically does 3-5x
 * that. The frequency isn't location-protected (only the network's name is),
 * so no permission is needed.
 */
object WifiBand {
    fun on24GHz(context: Context): Boolean {
        val mhz = frequencyMhz(context) ?: return false
        return mhz in 2400..2500
    }

    private fun frequencyMhz(context: Context): Int? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val fromCaps = if (Build.VERSION.SDK_INT >= 31) (caps.transportInfo as? WifiInfo)?.frequency else null
        @Suppress("DEPRECATION")
        (fromCaps?.takeIf { it > 0 }
            ?: context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.frequency)
            ?.takeIf { it > 0 }
    }.getOrNull()
}
