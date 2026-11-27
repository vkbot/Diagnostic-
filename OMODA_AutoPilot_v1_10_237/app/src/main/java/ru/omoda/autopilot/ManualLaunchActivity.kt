package ru.omoda.autopilot

import android.app.Activity
import android.os.Bundle
import kotlin.concurrent.thread

/**
 * Прозрачная Activity, которую открывает кнопка в уведомлении.
 * Нажатие пользователя даёт этой Activity право запускать другие приложения
 * (освобождение от ограничений фонового запуска), поэтому кнопка работает
 * даже там, где автоматический запуск из сервиса заблокирован.
 */
class ManualLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        thread(name = "ManualLaunch") {
            val settings = SettingsRepository(appContext)
            val lines = mutableListOf<String>()
            val apps = settings.getApps()
            if (apps.isEmpty()) lines.add("Список приложений пуст")
            for (app in apps) {
                val outcome = AppLauncher(appContext).launch(app.packageName)
                lines.add("${app.packageName}: ${outcome.describe()}")
                Thread.sleep(app.delayAfterLaunchMs.coerceIn(0L, AutoStartConfig.MAX_DELAY_MS))
            }
            lines.forEach { settings.addEvent(it) }
            settings.saveLastResult("Ручной запуск из уведомления:\n" + lines.joinToString("\n"))
        }
        finish()
    }
}
