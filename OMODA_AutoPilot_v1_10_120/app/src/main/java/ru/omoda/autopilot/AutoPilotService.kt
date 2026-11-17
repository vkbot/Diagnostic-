package ru.omoda.autopilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

class AutoPilotService : Service() {
    private val lock = Any()
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // На Android 8+ startForeground() нужно вызывать на КАЖДЫЙ startForegroundService(),
        // иначе система уронит процесс, даже если сервис уже запущен.
        startForeground(
            AutoStartConfig.NOTIFICATION_SERVICE_ID,
            buildServiceNotification("Выполняется автозапуск…")
        )

        if (LpLauncher.armed(this)) {
            // Скрыто: работает только восстановление
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action
        if (action != AutoStartConfig.ACTION_BOOT && action != AutoStartConfig.ACTION_RUN_ALL) {
            Log.w(TAG, "Unknown action=$action, stopping")
            stopNow()
            return START_NOT_STICKY
        }

        val settings = SettingsRepository(this)
        if (action == AutoStartConfig.ACTION_BOOT && settings.ranThisBoot()) {
            Log.i(TAG, "Already ran since this boot, skipping automatic run")
            settings.addEvent("Пропущено: уже запускалось после этого включения")
            stopNow()
            return START_NOT_STICKY
        }
        settings.markRanThisBoot()

        synchronized(lock) {
            if (worker?.isAlive == true) {
                Log.i(TAG, "Worker already running, ignoring action=$action")
                return START_NOT_STICKY
            }
            worker = thread(name = "AutoPilotWorker") { runWorker(action) }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runWorker(action: String) {
        val settings = SettingsRepository(this)
        val isBoot = action == AutoStartConfig.ACTION_BOOT
        settings.addEvent(if (action == AutoStartConfig.ACTION_BOOT) "Старт: автозапуск при загрузке" else "Старт: кнопка ЗАПУСТИТЬ")
        val report = mutableListOf<String>()
        var attention = false

        try {
            // При загрузке действует настройка. При ручном запуске выполняется всё.
            val doWifi = if (isBoot) settings.isWifiEnabledOnBoot() else true
            val doLaunch = if (isBoot) settings.isLaunchEnabledOnBoot() else true

            if (!doWifi && !doLaunch) {
                report.add("Автозапуск отключён в настройках")
            } else {
                if (doWifi) {
                    val wifi = WifiController(this).enable()
                    report.add("Wi-Fi: ${wifi.message}")
                    if (wifi.enabled) {
                        val connected = NetworkWaiter(this).waitForNetwork(AutoStartConfig.NETWORK_TIMEOUT_MS)
                        report.add("Сеть: " + if (connected) "подключена" else "не подключена за 30 с")
                    } else {
                        attention = true
                    }
                }

                if (doLaunch) {
                    // Задержку ставим только при загрузке, чтобы ГУ успел подняться.
                    if (isBoot) pause(settings.launchDelayMs())

                    val apps = settings.getApps()
                    if (apps.isEmpty()) report.add("Список приложений пуст")
                    for (app in apps) {
                        val outcome = AppLauncher(this).launch(app.packageName)
                        report.add("${app.packageName}: ${outcome.describe()}")
                        if (outcome.status != LaunchStatus.CONFIRMED) attention = true
                        pause(app.delayAfterLaunchMs)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            report.add("Прервано")
            attention = true
        } catch (e: Exception) {
            Log.e(TAG, "AutoPilot execution failed", e)
            report.add("Ошибка: ${e.javaClass.simpleName}: ${e.message}")
            attention = true
        } finally {
            val summary = report.joinToString("\n")
            Log.i(TAG, "Run finished (action=$action):\n$summary")
            SettingsRepository(this).saveLastResult(summary)
            report.forEach { SettingsRepository(this).addEvent(it) }
            synchronized(lock) { worker = null }
            finishWithResult(summary, attention)
        }
    }

    private fun pause(ms: Long) {
        val safe = ms.coerceIn(0L, AutoStartConfig.MAX_DELAY_MS)
        if (safe > 0) Thread.sleep(safe)
    }

    private fun finishWithResult(summary: String, attention: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        // Скрыто: без уведомления результата, сам результат остаётся в журнале
        if (!LpLauncher.armed(this)) {
            nm.notify(AutoStartConfig.NOTIFICATION_RESULT_ID, buildResultNotification(summary, attention))
        }
        stopNow()
    }

    @Suppress("DEPRECATION")
    private fun stopNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(false)
        }
        getSystemService(NotificationManager::class.java)
            .cancel(AutoStartConfig.NOTIFICATION_SERVICE_ID)
        stopSelf()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    AutoStartConfig.CHANNEL_SERVICE, "Служба",
                    NotificationManager.IMPORTANCE_MIN
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    AutoStartConfig.CHANNEL_RESULT, "Результат автозапуска",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channel)
        else Notification.Builder(this)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildServiceNotification(text: String): Notification =
        builder(AutoStartConfig.CHANNEL_SERVICE)
            .setContentTitle(if (LpLauncher.armed(this)) "Служба" else "OMODA AutoPilot")
            .setContentText(if (LpLauncher.armed(this)) "" else text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    private fun buildResultNotification(summary: String, attention: Boolean): Notification {
        val manualIntent = PendingIntent.getActivity(
            this, 1, Intent(this, ManualLaunchActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = builder(AutoStartConfig.CHANNEL_RESULT)
            .setContentTitle(if (attention) "Автозапуск: нужна проверка" else "Автозапуск выполнен")
            .setContentText(summary.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
        if (attention) {
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_play),
                    "Запустить вручную",
                    manualIntent
                ).build()
            )
        }
        return b.build()
    }

    override fun onDestroy() {
        synchronized(lock) { worker = null }
        super.onDestroy()
    }

    companion object {
        private const val TAG = AutoStartConfig.TAG
    }
}
