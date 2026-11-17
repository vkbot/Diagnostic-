package ru.omoda.autopilot

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import kotlin.math.abs

enum class AddResult { ADDED, INVALID, DUPLICATE }

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(AutoStartConfig.PREFS, Context.MODE_PRIVATE)

    fun isWifiEnabledOnBoot(): Boolean = prefs.getBoolean(AutoStartConfig.KEY_WIFI, true)

    fun setWifiEnabledOnBoot(value: Boolean) {
        prefs.edit().putBoolean(AutoStartConfig.KEY_WIFI, value).apply()
    }

    fun isLaunchEnabledOnBoot(): Boolean = prefs.getBoolean(AutoStartConfig.KEY_LAUNCH, true)

    fun setLaunchEnabledOnBoot(value: Boolean) {
        prefs.edit().putBoolean(AutoStartConfig.KEY_LAUNCH, value).apply()
    }

    fun launchDelayMs(): Long =
        prefs.getLong(AutoStartConfig.KEY_DELAY, AutoStartConfig.DEFAULT_DELAY_MS)
            .coerceIn(0L, AutoStartConfig.MAX_DELAY_MS)

    fun setLaunchDelayMs(value: Long) {
        prefs.edit()
            .putLong(AutoStartConfig.KEY_DELAY, value.coerceIn(0L, AutoStartConfig.MAX_DELAY_MS))
            .apply()
    }

    /**
     * Флаг «уже запускалось после текущего включения».
     * Сбрасывается сам: при перезагрузке меняется boot_id ядра Linux,
     * поэтому сохранённое значение перестаёт совпадать.
     */
    fun ranThisBoot(): Boolean {
        if (!prefs.getBoolean(AutoStartConfig.KEY_RAN_THIS_BOOT, false)) return false
        val savedId = prefs.getString(AutoStartConfig.KEY_BOOT_ID, null).orEmpty()
        val currentId = readBootId()
        if (savedId.isNotEmpty() && currentId.isNotEmpty()) return savedId == currentId
        // Запасной вариант, если /proc недоступен: сравниваем время старта системы.
        val savedTime = prefs.getLong(AutoStartConfig.KEY_BOOT_TIME, 0L)
        return abs(savedTime - currentBootTime()) < AutoStartConfig.BOOT_TIME_TOLERANCE_MS
    }

    fun markRanThisBoot() {
        prefs.edit()
            .putBoolean(AutoStartConfig.KEY_RAN_THIS_BOOT, true)
            .putString(AutoStartConfig.KEY_BOOT_ID, readBootId())
            .putLong(AutoStartConfig.KEY_BOOT_TIME, currentBootTime())
            .apply()
    }

    /** Журнал событий (то, что раньше показывали уведомления). Новые сверху. */
    fun addEvent(text: String) {
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date())
        val updated = (listOf("$time  $text") + events()).take(AutoStartConfig.MAX_EVENTS)
        prefs.edit().putString(AutoStartConfig.KEY_EVENTS, JSONArray(updated).toString()).apply()
    }

    fun events(): List<String> =
        try {
            val array = JSONArray(prefs.getString(AutoStartConfig.KEY_EVENTS, null) ?: "[]")
            (0 until array.length()).map { array.optString(it) }
        } catch (e: Exception) {
            emptyList()
        }

    fun getApps(): List<AutoStartApp> {
        val raw = prefs.getString(AutoStartConfig.KEY_APPS, null)
            ?: return listOf(AutoStartApp(AutoStartConfig.DEFAULT_MUSIC_PACKAGE))
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val pkg = obj.optString("package").trim()
                if (pkg.isBlank()) null
                else AutoStartApp(pkg, obj.optLong("delay", AutoStartConfig.DEFAULT_APP_DELAY_MS))
            }
        } catch (e: Exception) {
            listOf(AutoStartApp(AutoStartConfig.DEFAULT_MUSIC_PACKAGE))
        }
    }

    fun addApp(packageName: String): AddResult {
        val pkg = packageName.trim()
        if (!isValidPackageName(pkg)) return AddResult.INVALID
        val current = getApps().toMutableList()
        if (current.any { it.packageName == pkg }) return AddResult.DUPLICATE
        current.add(AutoStartApp(pkg))
        saveApps(current)
        return AddResult.ADDED
    }

    fun removeApp(packageName: String) {
        saveApps(getApps().filterNot { it.packageName == packageName })
    }

    fun saveLastResult(text: String) {
        prefs.edit()
            .putString(AutoStartConfig.KEY_LAST_RESULT, text)
            .putLong(AutoStartConfig.KEY_LAST_RESULT_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun saveApps(apps: List<AutoStartApp>) {
        val array = JSONArray()
        apps.forEach {
            array.put(JSONObject().put("package", it.packageName).put("delay", it.delayAfterLaunchMs))
        }
        prefs.edit().putString(AutoStartConfig.KEY_APPS, array.toString()).apply()
    }

    private fun readBootId(): String =
        try {
            File(AutoStartConfig.BOOT_ID_PATH).readText().trim()
        } catch (e: Exception) {
            ""
        }

    private fun currentBootTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    companion object {
        private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun isValidPackageName(value: String): Boolean = PACKAGE_REGEX.matches(value)
    }
}
