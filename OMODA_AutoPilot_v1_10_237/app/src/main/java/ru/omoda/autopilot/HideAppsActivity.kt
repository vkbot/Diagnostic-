package ru.omoda.autopilot

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Скрытие приложений плиткой с иконками. Отметка плитки меняет её состояние,
 * «ПРИМЕНИТЬ» скрывает или возвращает только отмеченные плитки.
 */
class HideAppsActivity : Activity() {

    private class App(val label: String, val pkg: String, val system: Boolean, val icon: Drawable?)

    private val protectedPkgs: Set<String> get() = ProtectedPackages.SYSTEM + ProtectedPackages.CAR
    private val apps = mutableListOf<App>()
    private val hiddenNow = mutableSetOf<String>()      // сейчас скрыты
    private val target = mutableMapOf<String, Boolean>() // отметки, которые ещё не применены

    private lateinit var grid: LinearLayout
    private lateinit var status: TextView
    private lateinit var showSystem: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(12), dp(12), dp(12))
        root.setBackgroundColor(COLOR_BG)

        root.addView(actionButton("НАЗАД") { finish() })
        root.addView(labelView("Скрытие приложений", 18f))

        showSystem = CheckBox(this)
        showSystem.text = "Показать системные приложения"
        showSystem.setTextColor(COLOR_TEXT)
        showSystem.setOnCheckedChangeListener { _, _ -> renderGrid() }
        root.addView(showSystem)

        status = labelView("Отметьте плитку, чтобы скрыть или вернуть приложение. Затем нажмите «ПРИМЕНИТЬ».", 13f, 8)
        root.addView(status)

        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        root.addView(grid)

        root.addView(actionButton("ПРИМЕНИТЬ") { apply() })
        root.addView(actionButton("СБРОСИТЬ ОТМЕТКИ") {
            target.clear()
            renderGrid()
            status.text = "Отметки сброшены"
        })

        setContentView(ScrollView(this).also { it.setBackgroundColor(COLOR_BG); it.addView(root) })
        load()
    }

    private fun hiddenFile(): File = File(filesDir, "hidden_apps.txt")

    /** Файл хранит строки «пакет|название», чтобы скрытые приложения можно было показать даже без списка системы. */
    private fun readHidden(): Map<String, String> =
        if (!hiddenFile().exists()) emptyMap()
        else hiddenFile().readLines().filter { it.isNotBlank() }.associate {
            val parts = it.split("|", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: parts[0])
        }

    private fun writeHidden(map: Map<String, String>) {
        hiddenFile().writeText(map.entries.joinToString("\n") { "${it.key}|${it.value}" })
    }

    private fun shell(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        // Не ждём вечно: как в диспетчере, с лимитом времени
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
        out
    } catch (e: Exception) {
        "ошибка: ${e.message}"
    }

    private fun load() {
        Thread {
            val pm = packageManager
            val list = mutableListOf<App>()
            val seen = mutableSetOf<String>()
            for (info in pm.getInstalledApplications(0)) {
                val pkg = info.packageName
                if (pkg in protectedPkgs || pkg == packageName) continue
                seen.add(pkg)
                // Системное = лежит в системном разделе и не обновлено пользователем
                val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
                list.add(App(pm.getApplicationLabel(info).toString(), pkg, system, icon))
            }
            val hidden = readHidden()
            for ((pkg, label) in hidden) {
                if (pkg in seen || pkg in protectedPkgs || pkg == packageName) continue
                list.add(App(label, pkg, false, null))
            }
            runOnUiThread {
                apps.clear()
                apps.addAll(list.sortedBy { it.label.lowercase() })
                hiddenNow.clear()
                hiddenNow.addAll(hidden.keys)
                renderGrid()
            }
        }.start()
    }

    private fun renderGrid() {
        grid.removeAllViews()
        val shown = apps.filter { showSystem.isChecked || !it.system }
        if (shown.isEmpty()) {
            grid.addView(labelView("Приложений нет", 14f))
            return
        }
        var row: LinearLayout? = null
        shown.forEachIndexed { i, app ->
            if (i % 4 == 0) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                grid.addView(r)
                row = r
            }
            row?.addView(tile(app))
        }
    }

    private fun tile(app: App): View {
        val current = hiddenNow.contains(app.pkg)
        val marked = target.containsKey(app.pkg)
        val wanted = target[app.pkg] ?: current

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(6), dp(8), dp(6), dp(8))
        box.background = GradientDrawable().apply {
            setColor(if (marked) Color.parseColor("#1E3A4A") else COLOR_CARD)
            cornerRadius = dp(12).toFloat()
            if (marked) setStroke(dp(2), COLOR_ACCENT)
        }
        box.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }

        val icon = ImageView(this)
        icon.setImageDrawable(app.icon ?: getDrawable(android.R.drawable.sym_def_app_icon))
        box.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))

        val name = labelView(app.label, 12f, 4)
        name.gravity = Gravity.CENTER
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END
        box.addView(name)

        // Состояние: уже скрыто, будет скрыто, будет возвращено
        val stateText = when {
            wanted && !current -> "будет скрыто"
            !wanted && current -> "будет возвращено"
            wanted -> "скрыто"
            else -> ""
        }
        val badge = labelView(stateText, 11f)
        badge.gravity = Gravity.CENTER
        if (wanted) badge.setTextColor(Color.parseColor("#FF6B6B"))
        box.addView(badge)

        if (app.system) {
            val tag = labelView("системное", 10f)
            tag.gravity = Gravity.CENTER
            box.addView(tag)
        }
        box.setOnClickListener { toggle(app.pkg) }
        return box
    }

    private fun toggle(pkg: String) {
        val current = hiddenNow.contains(pkg)
        val want = !(target[pkg] ?: current)
        if (want == current) target.remove(pkg) else target[pkg] = want
        renderGrid()
    }

    /** Применяет отметки: pm hide для отмеченных скрыть и pm unhide для отмеченных вернуть. */
    private fun apply() {
        val changes = target.toMap()
        if (changes.isEmpty()) {
            status.text = "Ничего не отмечено"
            return
        }
        status.text = "Применяю..."
        Thread {
            val map = readHidden().toMutableMap()
            val labels = apps.associate { it.pkg to it.label }
            val hiddenDone = mutableListOf<String>()
            val restoredDone = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for ((pkg, want) in changes) {
                // Защита на последнем шаге: системные и автомобильные пакеты не трогаем, даже если они попали в список
                if (pkg in protectedPkgs || pkg == packageName) continue
                val label = labels[pkg] ?: map[pkg] ?: pkg
                if (want) {
                    if (shell("pm hide $pkg").contains("hidden state: true")) {
                        map[pkg] = label
                        hiddenDone.add(label)
                    } else {
                        failed.add(label)
                    }
                } else {
                    if (shell("pm unhide $pkg").contains("hidden state: false")) {
                        map.remove(pkg)
                        restoredDone.add(label)
                    } else {
                        failed.add(label)
                    }
                }
            }
            writeHidden(map)
            val parts = mutableListOf<String>()
            if (hiddenDone.isNotEmpty()) parts.add("Скрыто: " + hiddenDone.joinToString(", "))
            if (restoredDone.isNotEmpty()) parts.add("Возвращено: " + restoredDone.joinToString(", "))
            if (failed.isNotEmpty()) parts.add("Не удалось: " + failed.joinToString(", "))
            val msg = parts.joinToString(". ").ifEmpty { "Ничего не изменено" }
            SettingsRepository(applicationContext).addEvent("скрытие приложений: $msg")
            runOnUiThread {
                target.clear()
                hiddenNow.clear()
                hiddenNow.addAll(map.keys)
                renderGrid()
                status.text = msg
            }
        }.start()
    }
}
