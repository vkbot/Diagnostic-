package ru.omoda.autopilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != AutoStartConfig.ACTION_QUICKBOOT_POWERON) return

        if (LpLauncher.armed(context)) {
            Log.i(TAG, "Hidden mode: autostart skipped after $action")
            return
        }
        // Климат-профиль срабатывает при старте системы, одновременно с Wi-Fi, а не после открытия экрана
        if (Scenarios.all(context).any { it.enabled }) {
            val scen = Intent(context, ScenarioService::class.java).setAction(ScenarioService.ACTION_LAUNCH)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(scen)
                else context.startService(scen)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start scenario service after boot", e)
            }
        }
        val settings = SettingsRepository(context)
        if (!settings.isWifiEnabledOnBoot() && !settings.isLaunchEnabledOnBoot()) {
            Log.i(TAG, "Wi-Fi and launch on boot both disabled; ignoring $action")
            return
        }

        val service = Intent(context, AutoPilotService::class.java)
            .setAction(AutoStartConfig.ACTION_BOOT)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
            Log.i(TAG, "Service start requested after $action")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start AutoPilot service after boot", e)
        }
    }

    companion object {
        private const val TAG = AutoStartConfig.TAG
    }
}
