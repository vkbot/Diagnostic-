package ru.omoda.autopilot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock

/** Ждёт появления активной сети (Wi-Fi или интернет), но не дольше таймаута. */
class NetworkWaiter(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun waitForNetwork(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isConnected()) return true
            Thread.sleep(POLL_MS)
        }
        return isConnected()
    }

    fun isConnected(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val POLL_MS = 500L
    }
}
