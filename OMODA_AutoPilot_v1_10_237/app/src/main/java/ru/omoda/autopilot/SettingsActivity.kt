package ru.omoda.autopilot

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

class SettingsActivity : Activity() {
    private lateinit var settings: SettingsRepository
    private lateinit var appsContainer: LinearLayout

    override fun onResume() {
        super.onResume()
        window.decorView.applyDarkTheme()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(16))
        setContentView(ScrollView(this).also { it.addView(root) })

        window.decorView.setBackgroundColor(COLOR_BG)
        root.addView(labelView("Настройки", 22f))
        root.addView(actionButton("Назад") { finish() })

        root.addView(checkBox("Включать Wi-Fi при загрузке", settings.isWifiEnabledOnBoot()) {
            settings.setWifiEnabledOnBoot(it)
        })
        root.addView(checkBox("Запускать приложения при загрузке", settings.isLaunchEnabledOnBoot()) {
            settings.setLaunchEnabledOnBoot(it)
        })

        root.addView(labelView("Задержка перед запуском при загрузке, мс", 15f, 16))
        val delay = EditText(this)
        delay.inputType = InputType.TYPE_CLASS_NUMBER
        delay.setText(settings.launchDelayMs().toString())
        root.addView(delay)
        root.addView(actionButton("Сохранить задержку") {
            val value = delay.text.toString().toLongOrNull()
            if (value == null || value < 0 || value > AutoStartConfig.MAX_DELAY_MS) {
                toast("Введи число от 0 до 60000")
            } else {
                settings.setLaunchDelayMs(value)
                toast("Сохранено")
            }
        })

        root.addView(labelView("Приложения автозапуска (package ID)", 16f, 20))
        appsContainer = LinearLayout(this)
        appsContainer.orientation = LinearLayout.VERTICAL
        root.addView(appsContainer)

        root.addView(actionButton("Добавить приложение") { showAppPicker() })

        refreshApps()
    }

    /** Список запускаемых приложений с иконками: тап по приложению добавляет его в автозапуск. */
    private fun showAppPicker() {
        val dialog = android.app.Dialog(this)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setBackgroundColor(COLOR_BG)
        column.setPadding(dp(16), dp(16), dp(16), dp(16))
        column.addView(labelView("Выберите приложение", 18f))
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        column.addView(android.widget.ScrollView(this).also { it.addView(body) })
        column.addView(actionButton("НАЗАД") { dialog.dismiss() })
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { Pair(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.first.lowercase() }
        for ((label, pkg) in apps) {
            val btn = actionButton(label) {
                when (settings.addApp(pkg)) {
                    AddResult.ADDED -> {
                        refreshApps()
                        toast("Добавлено: $label")
                        dialog.dismiss()
                    }
                    AddResult.DUPLICATE -> toast("Уже в списке: $label")
                    AddResult.INVALID -> toast("Не удалось добавить: $label")
                }
            }
            try {
                val icon = pm.getApplicationIcon(pkg)
                icon.setBounds(0, 0, dp(40), dp(40))
                btn.setCompoundDrawables(icon, null, null, null)
                btn.compoundDrawablePadding = dp(12)
            } catch (e: Exception) {
                // без иконки кнопка всё равно работает
            }
            body.addView(btn)
        }
        dialog.setContentView(column)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.show()
    }

    private fun appTitle(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun refreshApps() {
        appsContainer.removeAllViews()
        val apps = settings.getApps()
        if (apps.isEmpty()) {
            appsContainer.addView(labelView("Список пуст", 14f))
        }
        for (app in apps) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.addView(
                labelView(appTitle(app.packageName), 15f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(actionButton("Удалить") {
                settings.removeApp(app.packageName)
                refreshApps()
            })
            appsContainer.addView(row)
        }
    }

    private fun checkBox(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox {
        val box = CheckBox(this)
        box.setTextColor(COLOR_TEXT)
        box.text = label
        box.isChecked = checked
        box.setOnCheckedChangeListener { _, value -> onChange(value) }
        return box
    }
}
