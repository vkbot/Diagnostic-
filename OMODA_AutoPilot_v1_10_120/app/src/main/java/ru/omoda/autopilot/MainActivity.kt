package ru.omoda.autopilot

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private val GREY: Int = Color.parseColor("#6B7280")
private val GREEN: Int = Color.parseColor("#4ADE80")
private val AMBER: Int = Color.parseColor("#FBBF24")

class MainActivity : Activity() {
    private lateinit var settings: SettingsRepository
    private lateinit var wifiIcon: ImageView
    private lateinit var wifiLabel: TextView
    private lateinit var autoIcon: ImageView
    private lateinit var autoLabel: TextView
    private lateinit var eventsView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0
    private var lastWifi = -1
    private var lastAuto: Boolean? = null

    private val wifiManager: WifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshIndicators()
            tick++
            if (tick % 4 == 0) refreshEvents()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)
        LpLauncher.check(applicationContext, false)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(16), dp(20), dp(24))
        setContentView(ScrollView(this).also { it.setBackgroundColor(COLOR_BG); it.addView(root) })

        // Верхняя строка: название слева, индикаторы Wi-Fi и автозапуска справа
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val title = labelView("Помощник", 26f)
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        top.addView(title)

        val wifi = makeIndicator()
        wifiIcon = wifi.first
        wifiLabel = wifi.second
        wifi.third.setOnClickListener { toggleWifi() }
        top.addView(wifi.third)

        val auto = makeIndicator()
        autoIcon = auto.first
        autoLabel = auto.second
        auto.third.setOnClickListener { toggleAutostart() }
        top.addView(auto.third)
        root.addView(top)

        root.addView(labelView(versionText(), 13f, 4))

        val run = actionButtonIcon("ЗАПУСТИТЬ", R.drawable.ic_play) { runNow() }
        run.textSize = 22f
        run.minimumHeight = dp(96)
        val runParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        runParams.topMargin = dp(20)
        runParams.bottomMargin = dp(10)
        root.addView(run, runParams)

        root.addView(actionButtonIcon("Установка APK", R.drawable.ic_download) { openInstall() })
        root.addView(actionButtonIcon("Скрытие приложений", R.drawable.ic_grid) {
            startActivity(Intent(this, HideAppsActivity::class.java))
        })
        root.addView(actionButtonIcon("Файловый менеджер", R.drawable.ic_folder) { openFiles() })
        root.addView(actionButtonIcon("Настройки", R.drawable.ic_sliders) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(actionButtonIcon("Настройки Android", R.drawable.ic_gear) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        })
        root.addView(actionButtonIcon("КОМАНДЫ ГОЛОСА", R.drawable.ic_sliders) {
            startActivity(Intent(this, VoiceCommandsActivity::class.java))
        })
        root.addView(actionButtonIcon("ГОЛОС ВКЛЮЧИТЬ", R.drawable.ic_play) { startVoice() })
        root.addView(actionButtonIcon("ГОЛОС ВЫКЛЮЧИТЬ", R.drawable.ic_power) { stopVoice() })
        root.addView(actionButtonIcon("ДИСПЕТЧЕР ЗАДАЧ", R.drawable.ic_grid) {
            startActivity(Intent(this, TaskManagerActivity::class.java))
        })
        root.addView(actionButtonIcon("СКРЫТЬ ЛАУНЧЕР", R.drawable.ic_grid) { hideWindow() })
        root.addView(actionButtonIcon("КЛИМАТ", R.drawable.ic_gear) {
            startActivity(Intent(this, ClimateActivity::class.java))
        })

        root.addView(labelView("Уведомления", 16f, 20))
        eventsView = labelView("", 13f, 4)
        root.addView(eventsView)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun versionText(): String =
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "Версия ${info.versionName} (код ${info.versionCode})"
        } catch (e: Exception) {
            "Версия неизвестна"
        }

    private fun openInstall() {
        startActivity(Intent(this, InstallTestActivity::class.java).putExtra("mode", "apk"))
    }

    private fun openFiles() {
        val intent = packageManager.getLaunchIntentForPackage("com.android.documentsui")
        if (intent == null) {
            toast("Приложение «Файлы» не найдено (com.android.documentsui)")
            return
        }
        startActivity(intent)
    }

    private fun runNow() {
        startService(Intent(this, AutoPilotService::class.java).setAction(AutoStartConfig.ACTION_RUN_ALL))
        toast("Запущено")
    }

    /** Запускает фоновое распознавание. Нужен доступ к микрофону. */
    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            toast("Разрешите микрофон и нажмите ещё раз")
            return
        }
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        toast("Голос включён")
    }

    private fun stopVoice() {
        startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
        toast("Голос выключен")
    }

    /** Выбор срока скрытия. Перед скрытием проверяем оверлей, без него режим не включаем. */
    private fun hideWindow() {
        val err = LpSquare.probe(this)
        if (err != null) {
            if (!LpSquare.canDraw(this)) {
                startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ))
            }
            toast("Скрытие не включено: $err")
            return
        }
        val hour = 3600_000L
        val options = listOf("1 час" to hour, "4 часа" to 4 * hour, "1 день" to 24 * hour, "7 дней" to 7 * 24 * hour)
        val dialog = android.app.Dialog(this)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setBackgroundColor(COLOR_BG)
        column.setPadding(dp(16), dp(16), dp(16), dp(16))
        column.addView(labelView("Скрыть ярлык на срок", 18f))
        column.addView(labelView("Вернуть: 5 нажатий подряд в правом верхнем углу экрана", 13f, 4))
        for ((label, ms) in options) {
            column.addView(actionButton(label) {
                dialog.dismiss()
                if (LpLauncher.arm(this, ms)) toast("Скрыто на $label")
                else toast("Срок не принят")
            })
        }
        column.addView(actionButton("Отмена") { dialog.dismiss() })
        dialog.setContentView(column)
        dialog.show()
    }

    /** Карточка-индикатор: иконка, подпись. Возвращает (иконка, подпись, карточка). */
    private fun makeIndicator(): Triple<ImageView, TextView, LinearLayout> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(8), dp(14), dp(8))
        row.background = GradientDrawable().apply {
            setColor(COLOR_CARD)
            cornerRadius = dp(20).toFloat()
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(10), 0, 0, 0) }
        val icon = ImageView(this)
        icon.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { setMargins(0, 0, dp(8), 0) }
        val label = TextView(this)
        label.textSize = 13f
        label.setTextColor(COLOR_TEXT)
        row.addView(icon)
        row.addView(label)
        return Triple(icon, label, row)
    }

    /** 0 — выключен, 1 — подключён, 2 — включён без сети, 3 — идёт подключение. */
    private fun wifiState(): Int {
        if (!wifiManager.isWifiEnabled) return 0
        val info = wifiManager.connectionInfo
        val st = info?.supplicantState
        return when {
            st == SupplicantState.COMPLETED && info != null && info.networkId != -1 -> 1
            st == SupplicantState.ASSOCIATING || st == SupplicantState.ASSOCIATED ||
                st == SupplicantState.AUTHENTICATING || st == SupplicantState.FOUR_WAY_HANDSHAKE ||
                st == SupplicantState.GROUP_HANDSHAKE || st == SupplicantState.SCANNING -> 3
            else -> 2
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        wifiManager.isWifiEnabled = !wifiManager.isWifiEnabled
        refreshIndicators()
    }

    private fun toggleAutostart() {
        val on = settings.isWifiEnabledOnBoot() || settings.isLaunchEnabledOnBoot()
        settings.setWifiEnabledOnBoot(!on)
        settings.setLaunchEnabledOnBoot(!on)
        refreshIndicators()
    }

    /** Иконку меняем только при смене состояния. Мигание при подключении — прозрачность. */
    private fun refreshIndicators() {
        val state = wifiState()
        if (state != lastWifi) {
            lastWifi = state
            val (res, color, text) = when (state) {
                0 -> Triple(R.drawable.ic_wifi_off, GREY, "Wi-Fi выкл")
                1 -> Triple(R.drawable.ic_wifi, GREEN, "Wi-Fi подключён")
                2 -> Triple(R.drawable.ic_wifi, AMBER, "Wi-Fi нет сети")
                else -> Triple(R.drawable.ic_wifi, AMBER, "Wi-Fi подключение…")
            }
            wifiIcon.setImageDrawable(tinted(res, color))
            wifiLabel.text = text
        }
        wifiIcon.alpha = if (state == 3 && tick % 2 == 1) 0.15f else 1f

        val on = settings.isWifiEnabledOnBoot() || settings.isLaunchEnabledOnBoot()
        if (on != lastAuto) {
            lastAuto = on
            autoIcon.setImageDrawable(tinted(R.drawable.ic_power, if (on) COLOR_ACCENT else GREY))
            autoLabel.text = if (on) "Автозапуск вкл" else "Автозапуск выкл"
        }
    }

    /** Из уведомлений убираем служебный шум: автоустановку, перехват, службу доступности, скрытие. */
    private fun refreshEvents() {
        val lines = settings.events().filter { line -> NOISE.none { line.contains(it) } }
        eventsView.text = lines.ifEmpty { listOf("Пока пусто") }.joinToString("\n")
    }

    companion object {
        private val NOISE = listOf("автоустановка", "[установка]", "A11y", "сервис", "скрытие", "установка по пакету")
    }
}
