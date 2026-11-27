package ru.omoda.autopilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Профили сценариев. Раз в 30 с проверяет условия (датчики улица и салон, логика И/ИЛИ).
 * Профиль срабатывает при переходе условия из ложного в истинное. В скрытом режиме не работает.
 */
class ScenarioService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var waitSince = 0L
    private var rpmId: Int? = null
    private var rpmFailed = false

    /** Обороты двигателя: ElecPower.ID_EP_MOTOR_SPEED (900 на холостом ходу по монитору). Читается через имя из CarService. */
    private fun engineRpm(): Double? {
        if (rpmFailed) return null
        try {
            if (rpmId == null) {
                val cl = dalvik.system.PathClassLoader(CAR_APK, ClassLoader.getSystemClassLoader())
                val f = Class.forName("mega.car.config.ElecPower", false, cl).getDeclaredField("ID_EP_MOTOR_SPEED")
                f.isAccessible = true
                rpmId = f.get(null) as Int
            }
        } catch (e: Throwable) {
            rpmFailed = true
            return null
        }
        val id = rpmId ?: return null
        // Зоны, где машина может отдать обороты: перебираем все, что встречались в тестах
        return listOf(0x400, 0x100, 0x50, 0x40, 0x10, 0x0, 0x1, 0x4, 0x8, 0x20, 0x80, 0x200, 0x800).firstNotNullOfOrNull { a ->
            (CarProps.get(id, a) as? Number)?.toDouble()
        }
    }

    /** Опрос оборотов раз в 3 секунды; после запуска — задержка из настроек и первая проверка профилей. */
    private val engineWait: Runnable = object : Runnable {
        override fun run() {
            worker.execute {
                val rpm = engineRpm()
                val started = rpm != null && rpm > 300
                // Результат виден на экране сценариев, в строке «Последний результат»
                if (started) {
                    val msg = "двигатель запущен (обороты ${rpm!!.toInt()}), жду задержку"
                    Scenarios.setLast(this@ScenarioService, msg)
                    SettingsRepository(this@ScenarioService).addEvent("сценарий: $msg")
                }
                // Если сигнала нет 10 минут, всё равно запускаем проверку, чтобы сценарии не пропали совсем
                val timeout = System.currentTimeMillis() - waitSince > 10 * 60_000L
                handler.post {
                    if (!started && timeout) {
                        val msg = "обороты не определены за 10 мин, проверка по таймауту"
                        Scenarios.setLast(this@ScenarioService, msg)
                        SettingsRepository(this@ScenarioService).addEvent("сценарий: $msg")
                    }
                    if (started || timeout) {
                        handler.removeCallbacks(tick)
                        handler.postDelayed(tick, Scenarios.launchDelaySec(this@ScenarioService) * 1000L)
                    } else {
                        handler.postDelayed(this@ScenarioService.engineWait, 3000L)
                    }
                }
            }
        }
    }
    private val wasTrue = HashMap<Long, Boolean>()
    @Volatile private var skipApplyOnce = false

    /** Одна проверка за запуск: сценарий срабатывает один раз при открытии приложения, потом служба останавливается. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            worker.execute {
                evaluate()
                handler.post {
                    releaseCpu()
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Служба", NotificationManager.IMPORTANCE_MIN))
        }
        val n = Notification.Builder(this)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Служба")
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 26) n.setChannelId(CHANNEL)
        startForeground(NOTIF_ID, n.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (LpLauncher.armed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manual = intent?.action == ACTION_APPLY
        if (!manual && Scenarios.all(this).none { it.enabled }) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (manual || intent?.action == ACTION_LAUNCH) holdCpu()
        if (manual) {
            val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
            // Ручная проверка: применяем только выбранный профиль и не даём опросу применить остальные
            skipApplyOnce = true
            worker.execute {
                Scenarios.all(this).filter { it.id == id }.forEach { applyProfile(it) }
            }
        }
        handler.removeCallbacks(tick)
        handler.removeCallbacks(engineWait)
        if (manual) {
            handler.post(tick)
        } else if (intent?.action == ACTION_LAUNCH) {
            // Старт системы или приложения: проверка сразу (с задержкой из настроек), без ожидания оборотов
            val delay = Scenarios.launchDelaySec(this).toLong()
            SettingsRepository(this).addEvent("сценарий: старт, проверка через $delay с")
            handler.postDelayed(tick, delay * 1000L)
        } else {
            // Служба больше не работает постоянно: без запуска приложения и ручной проверки ничего не делаем
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Пока экран выключен, таймеры системы «спят»: без удержания процессора задержка и проверка сработают только после включения экрана. */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun holdCpu() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "omoda:scenario").also {
            // Ограничение по времени: даже при ошибке удержание не останется навсегда
            it.acquire(15 * 60_000L)
        }
    }

    private fun releaseCpu() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // уже отпущено по таймауту
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseCpu()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(engineWait)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun evaluate() {
        val skip = skipApplyOnce
        skipApplyOnce = false
        if (LpLauncher.armed(this)) {
            handler.post { stopSelf() }
            return
        }
        val profiles = Scenarios.all(this)
        if (profiles.none { it.enabled }) {
            handler.post { stopSelf() }
            return
        }
        for (pr in profiles) {
            if (!pr.enabled) {
                wasTrue.remove(pr.id)
                continue
            }
            // Каждое условие считается отдельно; нет показания — условие ложно
            val results = pr.conds.map { c ->
                val t = (CarProps.get(if (c.src == "in") INSIDE else OUTSIDE, 0) as? Number)?.toDouble()
                if (t == null) false else if (c.op == "<") t < c.thr else t > c.thr
            }
            val cond = if (pr.conds.isEmpty()) false
                else if (pr.logic == "or") results.any { it } else results.all { it }
            if (cond && wasTrue[pr.id] != true && !skip) applyProfile(pr)
            wasTrue[pr.id] = cond
        }
    }

    /** Выполняет действия профиля и записывает результат в «Последний результат». */
    private fun applyProfile(pr: Scenarios.Profile) {
        val out = StringBuilder(pr.name + ": ")
        fun put(name: String, id: Int, area: Int, v: Any) {
            val err = CarProps.set(id, area, v)
            if (err == null && v is Int) Scenarios.noteSet(this@ScenarioService, id, area, v)
            out.append(name).append(if (err == null) " ок; " else " ($err); ")
        }
        if (pr.seats) {
            // В сценарии уровень 1–3 хранится как есть; у передних сидений на машине значение = уровень + 1
            put("сиденье водит.", DRV_SEAT, 16, pr.seatLevel + 1)
            put("сиденье пасс.", PASS_SEAT, 64, pr.seatLevel + 1)
        }
        if (pr.hi) {
            put("HI слева", LEFT_TEMP, 0, 30.5f)
            put("HI справа", RIGHT_TEMP, 64, 30.5f)
        }
        if (pr.front) put("лобовое", FRONT_DEFROST, 0, 1)
        if (pr.rear) put("заднее", REAR_DEFROST, 0, 1)
        if (pr.steer) put("руль", STEER_HEAT, 0, 1)
        Scenarios.setLast(this, out.toString())
        SettingsRepository(this).addEvent("сценарий «${pr.name}»: $out")
    }

    companion object {
        const val ACTION_APPLY = "ru.omoda.autopilot.SCENARIO_APPLY"
        const val ACTION_LAUNCH = "ru.omoda.autopilot.SCENARIO_LAUNCH"
        private const val CAR_APK = "/system/priv-app/CarService/CarService.apk"
        const val EXTRA_ID = "profile_id"
        private const val CHANNEL = "scen_min"
        private const val NOTIF_ID = 77
        private const val PERIOD_MS = 30_000L
        private const val OUTSIDE = CarIds.SENSOR_OUTSIDE
        private const val INSIDE = CarIds.SENSOR_INSIDE
        private const val DRV_SEAT = CarIds.SEAT_HEAT_DRV
        private const val PASS_SEAT = CarIds.SEAT_HEAT_PASS
        private const val LEFT_TEMP = CarIds.TEMP_LEFT
        private const val RIGHT_TEMP = CarIds.TEMP_RIGHT
        private const val FRONT_DEFROST = CarIds.FRONT_DEFROST
        private const val REAR_DEFROST = CarIds.REAR_DEFROST
        private const val STEER_HEAT = CarIds.STEER_HEAT
    }
}
