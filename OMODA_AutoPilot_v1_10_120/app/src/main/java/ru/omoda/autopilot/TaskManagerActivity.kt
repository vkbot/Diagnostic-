package ru.omoda.autopilot

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.TimeUnit

/** Диспетчер задач: запущенные приложения, память, остановка. Системное и сам помощник не трогаем. */
class TaskManagerActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var list: LinearLayout
    private val protectedPkgs = setOf(
        "android", "com.android.systemui", "com.android.launcher3", "com.android.settings"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(12), dp(12), dp(12))
        root.setBackgroundColor(COLOR_BG)
        root.addView(actionButton("НАЗАД") { finish() })
        root.addView(labelView("Диспетчер задач", 18f))
        info = labelView("Сбор данных...", 14f, 4)
        root.addView(info)
        root.addView(actionButton("ОБНОВИТЬ") { load() })
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        root.addView(list)
        setContentView(ScrollView(this).also { it.setBackgroundColor(COLOR_BG); it.addView(root) })
        load()
    }

    private fun load() {
        info.text = "Сбор данных..."
        Thread {
            val procs = processes().filter {
                it.first != packageName && !it.first.startsWith("vendor.") && !it.first.startsWith("android.hardware")
            }
            val (freeMb, totalMb) = memory()
            runOnUiThread {
                info.text = "Свободно памяти: $freeMb из $totalMb МБ. Приложений: ${procs.size}"
                list.removeAllViews()
                for ((pkg, rssKb) in procs) {
                    val mb = rssKb / 1024
                    if (pkg in protectedPkgs || pkg == packageName) {
                        list.addView(labelView("${appLabel(pkg)} — $mb МБ (системное)", 13f, 4))
                    } else {
                        val btn = actionButton("${appLabel(pkg)} — $mb МБ  ·  ОСТАНОВИТЬ") { stop(pkg) }
                        iconOf(pkg)?.let { icon ->
                            icon.setBounds(0, 0, dp(40), dp(40))
                            btn.setCompoundDrawables(icon, null, null, null)
                            btn.compoundDrawablePadding = dp(12)
                        }
                        list.addView(btn)
                    }
                }
            }
        }.start()
    }

    /** Название и иконка по пакету. Один раз читаем весь список установленных приложений. */
    private val appCache: Map<String, Pair<String, android.graphics.drawable.Drawable?>> by lazy {
        packageManager.getInstalledApplications(0).associate { ai ->
            ai.packageName to Pair(
                packageManager.getApplicationLabel(ai).toString(),
                try { packageManager.getApplicationIcon(ai.packageName) } catch (e: Exception) { null }
            )
        }
    }

    private fun appLabel(pkg: String): String = appCache[pkg]?.first ?: pkg

    private fun iconOf(pkg: String): android.graphics.drawable.Drawable? = appCache[pkg]?.second

    /** Список приложений по использованию памяти: имя пакета и суммарный RSS. */
    private fun processes(): List<Pair<String, Int>> {
        val fromPs = psProcesses()
        return if (fromPs.isNotEmpty()) fromPs else procScan()
    }

    /** Фолбэк, если ps пустой или недоступен: читаем /proc напрямую. */
    private fun procScan(): List<Pair<String, Int>> = File("/proc").listFiles().orEmpty()
        .filter { d -> d.name.all { it.isDigit() } }
        .mapNotNull { d ->
            try {
                val name = File(d, "cmdline").readText().substringBefore('\u0000').substringBefore(':')
                if (!name.contains('.')) return@mapNotNull null
                val rss = File(d, "status").readLines().firstOrNull { it.startsWith("VmRSS:") }
                    ?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() ?: 0
                Pair(name, rss)
            } catch (e: Exception) {
                null
            }
        }
        .groupBy { it.first }
        .map { (pkg, items) -> Pair(pkg, items.sumOf { it.second }) }
        .sortedByDescending { it.second }

    private fun psProcesses(): List<Pair<String, Int>> {
        val out = shell("ps -A")
        return out.lines().drop(1).mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 5) return@mapNotNull null
            val rss = p[4].toIntOrNull() ?: return@mapNotNull null
            val name = p.last().substringBefore(':')
            if (!name.contains('.')) return@mapNotNull null
            Pair(name, rss)
        }.groupBy { it.first }
            .map { (pkg, items) -> Pair(pkg, items.sumOf { it.second }) }
            .sortedByDescending { it.second }
    }

    private fun memory(): Pair<Long, Long> = try {
        val lines = File("/proc/meminfo").readLines()
        fun kb(key: String) = lines.first { it.startsWith(key) }.split(Regex("\\s+"))[1].toLong()
        Pair(kb("MemAvailable:") / 1024, kb("MemTotal:") / 1024)
    } catch (e: Exception) {
        Pair(0L, 0L)
    }

    /** Останавливает приложение командой force-stop. Процесс не убивает напрямую. */
    private fun stop(pkg: String) {
        Thread {
            val out = shell("am force-stop $pkg")
            runOnUiThread {
                toast(if (out.isBlank()) "Остановлено: $pkg" else out.trim())
                load()
            }
        }.start()
    }

    private fun shell(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroy()
        p.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        "ошибка: ${e.message}"
    }
}
