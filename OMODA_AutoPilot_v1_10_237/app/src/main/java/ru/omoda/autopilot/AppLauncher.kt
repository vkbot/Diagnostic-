package ru.omoda.autopilot

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

enum class LaunchStatus { CONFIRMED, UNCONFIRMED, NOT_INSTALLED, FAILED }

data class LaunchOutcome(val status: LaunchStatus, val detail: String = "") {
    fun describe(): String = when (status) {
        LaunchStatus.CONFIRMED -> "запущено, приложение на переднем плане"
        LaunchStatus.UNCONFIRMED -> "команда отправлена, но запуск не подтверждён (возможно, заблокирован прошивкой)"
        LaunchStatus.NOT_INSTALLED -> "не установлено или нет запускаемой Activity"
        LaunchStatus.FAILED -> "ошибка: $detail"
    }
}

class AppLauncher(context: Context) {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    fun launch(packageName: String): LaunchOutcome {
        val intent = try {
            pm.getLaunchIntentForPackage(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "PackageManager lookup failed for $packageName", e)
            return LaunchOutcome(LaunchStatus.FAILED, e.javaClass.simpleName)
        }
        if (intent == null) {
            Log.e(TAG, "No launch Activity for package=$packageName (not installed or no launcher activity)")
            return LaunchOutcome(LaunchStatus.NOT_INSTALLED)
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            Log.i(TAG, "startActivity sent for $packageName")
            if (waitForForeground(packageName)) {
                LaunchOutcome(LaunchStatus.CONFIRMED)
            } else {
                Log.w(TAG, "Foreground not confirmed for $packageName")
                LaunchOutcome(LaunchStatus.UNCONFIRMED)
            }
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Activity not found for $packageName", e)
            LaunchOutcome(LaunchStatus.FAILED, "Activity не найдена")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security denied launch of $packageName", e)
            LaunchOutcome(LaunchStatus.FAILED, "запрет системы")
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch $packageName", e)
            LaunchOutcome(LaunchStatus.FAILED, e.javaClass.simpleName)
        }
    }

    /**
     * Best-effort проверка: Android показывает процессы других приложений не во всех
     * случаях, поэтому отсутствие подтверждения означает «проверьте экран», а не «не запустилось».
     */
    private fun waitForForeground(packageName: String): Boolean {
        val am = appContext.getSystemService(ActivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + AutoStartConfig.FOREGROUND_CHECK_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val processes = am.runningAppProcesses.orEmpty()
            if (processes.any {
                    it.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        packageName in it.pkgList
                }
            ) return true
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    companion object {
        private const val TAG = AutoStartConfig.TAG
        private const val POLL_MS = 300L
    }
}
