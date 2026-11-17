package ru.omoda.autopilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Загрузка, обновление приложения и срабатывание таймера: проверяем дедлайн скрытия. */
class LpRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        LpLauncher.check(context.applicationContext, false)
    }
}
