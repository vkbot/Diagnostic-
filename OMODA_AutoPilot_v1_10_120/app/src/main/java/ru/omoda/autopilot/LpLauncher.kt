package ru.omoda.autopilot

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Скрытие ярлыков помощника (activity-alias) на заданное время с автовозвратом.
 * Меняем только компоненты своего пакета. Дедлайн хранится по часам стены:
 * elapsedRealtime после перезагрузки сбрасывается и ломает дедлайн.
 */
object LpLauncher {
    private const val PREF = "lp_hide"
    private const val K_ON = "on"
    private const val K_DEADLINE = "deadline"
    private const val K_ALL = "all"
    private const val K_KEEP = "keep"
    private const val TICK = "ru.omoda.lp.RESTORE_TICK"
    private const val CHECK_STEP_MS = 60_000L
    const val MAX_TTL_MS = 7L * 24 * 3600 * 1000

    private val handler = Handler(Looper.getMainLooper())
    private var tickTask: Runnable? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun armed(c: Context): Boolean = prefs(c).getBoolean(K_ON, false)

    /** Свои ярлыки: только компоненты нашего пакета. */
    private fun ownComponents(c: Context): Set<String> =
        setOf(
            ComponentName(c.packageName, "ru.omoda.autopilot.LpIcon").flattenToString(),
            ComponentName(c.packageName, "ru.omoda.autopilot.InterceptInstallActivity").flattenToString()
        )

    /** Скрывает свои ярлыки на ttlMs. Возвращает false, если срок вне допустимого диапазона. */
    fun arm(c: Context, ttlMs: Long): Boolean {
        if (ttlMs <= 0 || ttlMs > MAX_TTL_MS) return false
        prefs(c).edit()
            .putBoolean(K_ON, true)
            .putLong(K_DEADLINE, System.currentTimeMillis() + ttlMs)
            .putStringSet(K_ALL, ownComponents(c))
            .putStringSet(K_KEEP, emptySet())
            .commit()
        applyTarget(c)
        // Голос и автозапуск при скрытии не работают
        c.stopService(Intent(c, VoiceService::class.java))
        c.stopService(Intent(c, AutoPilotService::class.java))
        LpSquare.show(c.applicationContext)
        schedule(c)
        hideFromRecents(c)
        return true
    }

    /**
     * Имитация завершения: закрываем свои экраны, убираем задачи из обзора (превью не остаётся).
     * Сам процесс не убиваем: квадрат, сервис доступности и голос живут в нём же.
     */
    private fun hideFromRecents(c: Context) {
        val am = c.getSystemService(ActivityManager::class.java) ?: return
        am.appTasks.forEach { task ->
            try { task.setExcludeFromRecents(true) } catch (e: Exception) { }
            try { task.finishAndRemoveTask() } catch (e: Exception) { }
        }
    }

    /** Проверка дедлайна при старте, загрузке, обновлении и срабатывании будильника. */
    fun check(c: Context, force: Boolean) {
        if (!armed(c)) {
            ensureOwnEnabled(c)
            return
        }
        val deadline = prefs(c).getLong(K_DEADLINE, 0L)
        if (force || deadline <= System.currentTimeMillis()) {
            restore(c)
        } else {
            LpSquare.show(c.applicationContext)
            schedule(c)
        }
    }

    /** Полный возврат и сброс режима: состояние, будильник, таймер, квадрат. */
    fun restore(c: Context) {
        val all = prefs(c).getStringSet(K_ALL, emptySet()) ?: emptySet()
        for (s in all) setState(c, s, true)
        ownComponents(c).forEach { setState(c, it, true) }
        prefs(c).edit().clear().commit()
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingTick(c))
        tickTask?.let { handler.removeCallbacks(it) }
        tickTask = null
        LpSquare.remove()
        // сразу открываем приложение: квадрат = «вернуть и показать»
        c.startActivity(
            Intent(c, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }

    private fun applyTarget(c: Context) {
        val sp = prefs(c)
        val all = sp.getStringSet(K_ALL, emptySet()) ?: emptySet()
        val keep = sp.getStringSet(K_KEEP, emptySet()) ?: emptySet()
        for (s in all) setState(c, s, keep.contains(s))
    }

    private fun ensureOwnEnabled(c: Context) {
        ownComponents(c).forEach { setState(c, it, true) }
    }

    /** Единственная точка записи состояния. Чужой компонент — исключение, ничего не трогаем. */
    private fun setState(c: Context, flat: String, enabled: Boolean) {
        val cn = ComponentName.unflattenFromString(flat) ?: return
        if (cn.packageName != c.packageName) throw IllegalStateException("чужой компонент: $flat")
        val want = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (c.packageManager.getComponentEnabledSetting(cn) != want) {
            c.packageManager.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
        }
    }

    private fun schedule(c: Context) {
        val deadline = prefs(c).getLong(K_DEADLINE, 0L)
        val delay = maxOf(0L, minOf(CHECK_STEP_MS, deadline - System.currentTimeMillis()))
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, pendingTick(c))
        tickTask?.let { handler.removeCallbacks(it) }
        val app = c.applicationContext
        tickTask = Runnable { check(app, false) }.also { handler.postDelayed(it, delay) }
    }

    private fun pendingTick(c: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(
            c, 0, Intent(c, LpRestoreReceiver::class.java).setAction(TICK), flags
        )
    }
}
