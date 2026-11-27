package ru.omoda.autopilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Загрузка, обновление приложения и срабатывание таймера: проверяем дедлайн скрытия. */
class LpRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        LpLauncher.check(context.applicationContext, false)
        // После обновления приложение открываем само, если оно не скрыто
        if (intent?.action == android.content.Intent.ACTION_MY_PACKAGE_REPLACED && !LpLauncher.armed(context.applicationContext)) {
            context.startActivity(
                android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
