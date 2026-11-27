package ru.omoda.autopilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Внешний вход без иконок.
 * ru.omoda.lp.RESTORE — вернуть ярлыки; ru.omoda.lp.HIDE_OPEN — открыть экран управления.
 */
class LpCmd : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            "ru.omoda.lp.RESTORE" -> LpLauncher.restore(context.applicationContext)
            "ru.omoda.lp.HIDE_OPEN" -> context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
