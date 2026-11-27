package ru.omoda.autopilot

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

data class WifiResult(val enabled: Boolean, val message: String)

class WifiController(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Пробует включить Wi-Fi разными способами по очереди, пока один не сработает.
     * Каждый шаг пишется в результат, чтобы было видно, какой помог.
     */
    @Suppress("DEPRECATION")
    fun set(on: Boolean): WifiResult {
        return try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifi.isWifiEnabled == on) {
                WifiResult(true, if (on) "уже включён" else "уже выключен")
            } else {
                val log = mutableListOf<String>()
                val ok = attempt("1 WifiManager", log, wifi, on) { wifi.setWifiEnabled(on) } ||
                    attempt("2 Settings.Global", log, wifi, on) {
                        Settings.Global.putInt(appContext.contentResolver, "wifi_on", if (on) 1 else 0)
                    } ||
                    attempt("3 svc wifi", log, wifi, on) { shell("svc wifi ${if (on) "enable" else "disable"}") } ||
                    attempt("4 cmd wifi", log, wifi, on) { shell("cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}") } ||
                    attempt("5 рефлексия", log, wifi, on) { reflectSetWifi(wifi, on) }
                val text = log.joinToString("; ")
                Log.i(TAG, "Wi-Fi attempts: $text")
                if (ok) {
                    WifiResult(true, (if (on) "включён. " else "выключен. ") + text)
                } else {
                    WifiResult(false, "ни один способ не сработал. $text. " + diagnostics(appContext, wifi))
                }
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            Log.e(TAG, "Wi-Fi enable failed", e)
            WifiResult(false, "ошибка: ${e.javaClass.simpleName}")
        }
    }

    private fun attempt(name: String, log: MutableList<String>, wifi: WifiManager, on: Boolean, action: () -> Unit): Boolean =
        try {
            action()
            val ok = waitForState(wifi, on, 4000L)
            log.add("$name: " + (if (ok) "включил" else "нет эффекта"))
            ok
        } catch (e: SecurityException) {
            log.add("$name: запрещено системой")
            false
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            log.add("$name: ${e.javaClass.simpleName}")
            false
        }

    private fun shell(command: String) {
        // stderr сливаем в stdout: иначе переполненный буфер ошибок зависнет процесс
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        process.waitFor()
    }

    private fun reflectSetWifi(wifi: WifiManager, on: Boolean) {
        val method = WifiManager::class.java.getMethod("setWifiEnabled", java.lang.Boolean.TYPE)
        method.invoke(wifi, on)
    }

    /** Собирает причину отказа прямо в приложении, без adb. */
    @Suppress("DEPRECATION")
    private fun diagnostics(context: Context, wifi: WifiManager): String {
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1)
        val permission = if (context.checkSelfPermission("android.permission.CHANGE_WIFI_STATE") ==
            PackageManager.PERMISSION_GRANTED) "выдано" else "нет"
        val wifiOn = Settings.Global.getInt(context.contentResolver, "wifi_on", -1)
        return "режим полёта=$airplane, право CHANGE_WIFI_STATE=$permission, " +
            "wifi_on=$wifiOn, состояние Wi-Fi=${wifi.wifiState}"
    }

    @Suppress("DEPRECATION")
    private fun waitForState(wifi: WifiManager, expected: Boolean, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (wifi.isWifiEnabled == expected) return true
            Thread.sleep(POLL_MS)
        }
        return wifi.isWifiEnabled == expected
    }

    companion object {
        private const val TAG = AutoStartConfig.TAG
        private const val POLL_MS = 500L
    }
}
