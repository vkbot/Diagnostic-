package ru.omoda.autopilot

import android.content.Context

/**
 * Установка собственного APK перезапускает приложение, и результат на экране не виден.
 * Запоминаем ожидаемую версию до установки и сообщаем при следующем запуске.
 */
object InstallNotice {
    private const val PREF = "install_notice"

    fun expect(c: Context, name: String, versionCode: Int) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("name", name)
            .putInt("code", versionCode)
            .apply()
    }

    /** Текст для показа один раз после перезапуска; null — ожидания не было. */
    fun consume(c: Context): String? {
        val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val name = p.getString("name", null) ?: return null
        val expected = p.getInt("code", -1)
        p.edit().clear().apply()
        val now = c.packageManager.getPackageInfo(c.packageName, 0).versionCode
        return if (now >= expected) {
            "Установка «$name» применена: версия $now"
        } else {
            "Установка «$name» не применилась: версия $now, ожидалась $expected"
        }
    }
}
