package ru.omoda.autopilot

data class AutoStartApp(
    val packageName: String,
    val delayAfterLaunchMs: Long = AutoStartConfig.DEFAULT_APP_DELAY_MS
)

object AutoStartConfig {
    const val TAG = "OMODA-AutoPilot"

    const val PREFS = "autopilot_prefs"
    const val KEY_WIFI = "wifi_enabled"
    const val KEY_LAUNCH = "launch_enabled"
    const val KEY_APPS = "apps_json"
    const val KEY_DELAY = "launch_delay_ms"
    const val KEY_LAST_RESULT = "last_result"
    const val KEY_LAST_RESULT_TIME = "last_result_time"
    const val KEY_RAN_THIS_BOOT = "ran_this_boot"
    const val KEY_BOOT_ID = "boot_id_at_run"
    const val KEY_BOOT_TIME = "boot_time_at_run"
    const val KEY_EVENTS = "events_log"
    const val MAX_EVENTS = 40

    const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
    const val BOOT_TIME_TOLERANCE_MS = 30000L

    const val DEFAULT_MUSIC_PACKAGE = "com.github.uiautomator.test"
    const val DEFAULT_DELAY_MS = 7000L
    const val DEFAULT_APP_DELAY_MS = 5000L
    const val MAX_DELAY_MS = 60000L
    const val NETWORK_TIMEOUT_MS = 30000L
    const val WIFI_VERIFY_TIMEOUT_MS = 10000L
    const val FOREGROUND_CHECK_MS = 2500L

    const val ACTION_BOOT = "ru.omoda.autopilot.action.BOOT"
    const val ACTION_RUN_ALL = "ru.omoda.autopilot.action.RUN_ALL"
    const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

    const val CHANNEL_SERVICE = "omoda_service_min"
    const val CHANNEL_RESULT = "omoda_autopilot_result"
    const val NOTIFICATION_SERVICE_ID = 4107
    const val NOTIFICATION_RESULT_ID = 4108
}
