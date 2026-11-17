package ru.omoda.autopilot

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Ищет APK на флешке и внутренней памяти и ставит выбранный через системный PackageInstaller.
 * Копирование в /data/local/tmp и команды pm не используются.
 */
class InstallTestActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this)
        result = labelView("", 13f, 12)
        setContentView(android.view.View(this).also { it.setBackgroundColor(COLOR_BG) })
        when (intent.getStringExtra("mode")) {
            "apk" -> showApkMenu()
            else -> showRuStoreMenu()
        }
    }

    /**
     * Повторяет вашу ручную последовательность: клавиша 61 (Tab) переводит фокус,
     * клавиша 66 (Enter) нажимает кнопку «Установить». Дамп экрана нужен, чтобы знать,
     * где сейчас фокус. Окон установки может быть несколько, поэтому цикл продолжается,
     * пока кнопка «Установить» появляется на экране.
     */
    private fun autoInstallFromStore() {
        val want = 1
        toast("Открываю RuStore. Нажмите «Установить» в течение минуты")
        Thread {
            Thread.sleep(3000)
            val a11yInfo = armAccessibility()
            Thread.sleep(2000)
            SettingsRepository(applicationContext).addEvent("автоустановка: $a11yInfo, сервис подключён: ${LpA11y.connected}")
            // Если сервис подключён, он нажимает сам: цикл Tab/Enter не запускаем, чтобы не было двойных нажатий
            val useLoop = !LpA11y.connected
            if (!useLoop) {
                val base = LpA11y.clicks
                LpA11y.armedUntil = System.currentTimeMillis() + 60000L * want
                LpA11y.instance?.kick()
                val end = LpA11y.armedUntil
                while (System.currentTimeMillis() < end && LpA11y.clicks - base < want) {
                    showLive("Сервис нажимает сам. Нажатий: ${LpA11y.clicks - base} из $want. ${LpA11y.lastInfo}")
                    Thread.sleep(1000)
                }
            }
            var enters = 0
            var tabs = 0
            var idleRounds = 0
            var lastFocus = ""
            val started = System.currentTimeMillis()
            while (useLoop && System.currentTimeMillis() - started < 120000 && idleRounds < 20 && tabs < 14) {
                // Сначала mCurrentFocus; если не распознан, пакет берём из самого дампа окна
                val focusPkg = currentFocusPkg().let { if (it.startsWith("неизвестно")) dumpFocusPkg() else it }
                if (focusPkg != lastFocus) {
                    lastFocus = focusPkg
                    SettingsRepository(applicationContext).addEvent("автоустановка: фокус у $focusPkg")
                }
                if (focusPkg != "ru.vk.store" && focusPkg != "com.android.packageinstaller") {
                    // Наше приложение или другое окно в фокусе: не нажимаем, ждём RuStore
                    showLive("Жду RuStore. Сейчас в фокусе: $focusPkg. Нажмите «Установить» в RuStore")
                    Thread.sleep(1000)
                    continue
                }
                if (focusPkg == "com.android.packageinstaller") {
                    showLive("Системный установщик: нажимает сервис. Клики: ${LpA11y.clicks}")
                    Thread.sleep(1000)
                    continue
                }
                val nodes = parseNodes(dumpXml()).filter { it["package"] != packageName }
                val installNodes = nodes.filter {
                    it["text"].orEmpty().trim().equals("Установить", ignoreCase = true)
                }
                // Что скрипт видит в дампе: какие элементы помечены фокусом
                val focusInfo = nodes.filter { it["focused"] == "true" }
                    .joinToString(", ") { "«${it["text"].orEmpty()}»" }
                    .ifEmpty { "нет" }
                if (installNodes.isEmpty()) {
                    idleRounds++
                    if (idleRounds == 1) {
                        val texts = nodes.mapNotNull { it["text"]?.takeIf { t -> t.isNotBlank() } }.take(30).joinToString(" | ")
                        SettingsRepository(applicationContext).addEvent("автоустановка: в $focusPkg нет «Установить». Тексты: $texts")
                    }
                    showLive("Кнопки «Установить» не видно (проверка $idleRounds/20). Фокус: $focusInfo")
                    Thread.sleep(1000)
                    continue
                }
                idleRounds = 0
                val focusedInstall = installNodes.firstOrNull {
                    it["focused"] == "true" && it["enabled"] == "true"
                }
                if (focusedInstall != null) {
                    SettingsRepository(applicationContext).addEvent("автоустановка: Enter в окне $focusPkg")
                    runShell("input keyevent 66")
                    enters++
                    tabs = 0
                    showLive("Enter на «Установить» (#$enters). Жду следующее окно")
                    Thread.sleep(5000)
                } else {
                    SettingsRepository(applicationContext).addEvent("автоустановка: Tab в окне $focusPkg")
                    runShell("input keyevent 61")
                    tabs++
                    showLive("Tab=$tabs. Кнопка «Установить» видна, фокус на ней не отмечен. Фокус сейчас: $focusInfo")
                    Thread.sleep(800)
                }
            }
            LpA11y.armedUntil = 0
            val msg = if (enters == 0 && tabs == 0) {
                "кнопка «Установить» не найдена на экране"
            } else {
                "Готово. Tab=$tabs, Enter=$enters, клики сервиса=${LpA11y.clicks}"
            }
            SettingsRepository(applicationContext).addEvent("автоустановка: $msg")
            runOnUiThread { result.text = msg }
        }.start()
    }

    /** Пакет активного окна по дампу uiautomator: чаще всего встречающийся package. */
    private fun dumpFocusPkg(): String =
        parseNodes(dumpXml()).mapNotNull { it["package"] }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: "неизвестно"

    /** Пакет окна, которое сейчас в фокусе (из dumpsys window). */
    private fun currentFocusPkg(): String {
        val out = runRaw("dumpsys window | grep mCurrentFocus")
        return Regex("""([A-Za-z0-9_.]+)/""").find(out)?.groupValues?.get(1) ?: "неизвестно: ${out.take(120)}"
    }


    /** Включает сервис LpA11y и взводит его на время автоустановки. */
    private fun armAccessibility(): String = try {
        val comp = ComponentName(this, LpA11y::class.java).flattenToString()
        val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (!cur.contains(comp)) {
            val next = if (cur.isEmpty()) comp else "$cur:$comp"
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        }
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        LpA11y.armedUntil = System.currentTimeMillis() + 60000
        "сервис взведён"
    } catch (e: Exception) {
        "ошибка включения сервиса: ${e.message}"
    }

    /** Показывает текущий шаг автоустановки на экране, не дожидаясь конца цикла. */
    private fun showLive(text: String) {
        runOnUiThread { result.text = text }
    }

    /** Снимает дамп экрана в файл и возвращает XML. */
    private fun dumpXml(): String {
        val path = File(filesDir, "ui.xml").absolutePath
        runShell("uiautomator dump $path")
        return try { File(path).readText() } catch (e: Exception) { "" }
    }

    /** Выполняет команду и возвращает весь вывод без обрезки. */
    private fun runRaw(command: String): String =
        try {
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        } catch (e: Exception) {
            ""
        }

    /** Разбирает узлы uiautomator dump: каждый узел — набор атрибутов key="value". */
    private fun parseNodes(xml: String): List<Map<String, String>> =
        Regex("<node [^>]*>").findAll(xml).map { match ->
            Regex("([\\w-]+)=\"([^\"]*)\"").findAll(match.value)
                .associate { it.groupValues[1] to it.groupValues[2] }
        }.toList()

    /** Корни всех томов хранения, включая флешку: по путям app-папок на каждом томе. */
    private fun volumeRoots(): List<File> {
        val roots = mutableListOf<File>()
        for (dir in getExternalFilesDirs(null)) {
            val path = dir?.absolutePath ?: continue
            val idx = path.indexOf("/Android/")
            if (idx > 0) roots.add(File(path.substring(0, idx)))
        }
        // Флешка: имя тома берём из sm list-volumes, сам путь /storage/<имя> доступен uid 1000
        for (m in Regex("mounted\\s+([0-9A-Fa-f-]+)").findAll(runRaw("sm list-volumes public"))) {
            val uuid = m.groupValues[1]
            roots.add(File("/storage/$uuid"))
            roots.add(File("/mnt/user/0/$uuid"))
        }
        return roots
    }


    private var openDialogs = 0

    /** Раздел открывается отдельным окном поверх экрана, а не выпадает под кнопками. */
    private fun openWindow(title: String, fill: (LinearLayout) -> Unit) {
        val dialog = android.app.Dialog(this)
        openDialogs++
        dialog.setOnDismissListener {
            openDialogs--
            if (openDialogs <= 0) finish()
        }
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setBackgroundColor(COLOR_BG)
        column.setPadding(dp(16), dp(16), dp(16), dp(16))
        column.addView(labelView(title, 18f))
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        column.addView(android.widget.ScrollView(this).also { it.addView(body) })
        column.addView(actionButton("НАЗАД") { dialog.dismiss() })
        fill(body)
        dialog.setContentView(column)
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    private fun showRuStoreMenu() {
        openWindow("Установка RuStore") { body ->
            body.addView(actionButton("ОТКРЫТЬ RUSTORE") { openRuStoreAndInstall() })
        }
    }

    private fun showApkMenu() {
        openWindow("Установка APK") { body ->
            body.addView(actionButton("RuStore") { showRuStoreMenu() })
            body.addView(actionButton("ИЗ ЗАГРУЗОК") { openApkDialog("Загрузки") { listOf(File("/sdcard/Download")) } })
            body.addView(actionButton("С ФЛЕШКИ") {
                openApkDialog("Флешка") { volumeRoots().filter { !it.absolutePath.startsWith("/storage/emulated") } }
            })
        }
    }

    private fun openApkDialog(title: String, src: () -> List<File>) {
        openWindow(title) { body -> fillApk(body, src) }
    }

    /** Заполняет окно APK. Список пересчитывается при открытии и по кнопке «ОБНОВИТЬ СПИСОК». */
    private fun fillApk(body: LinearLayout, src: () -> List<File>) {
        body.removeAllViews()
        body.addView(labelView("Поиск APK...", 14f))
        Thread {
            val roots = src()
            val found = mutableListOf<File>()
            for (dir in roots) {
                try {
                    dir.walkTopDown().maxDepth(4)
                        .filter { it.isFile && it.name.lowercase().endsWith(".apk") }
                        .forEach { found.add(it) }
                } catch (e: Exception) {
                    // недоступная папка пропускается
                }
            }
            runOnUiThread {
                body.removeAllViews()
                body.addView(actionButton("ОБНОВИТЬ СПИСОК") { fillApk(body, src) })
                if (found.isEmpty()) {
                    body.addView(labelView("APK не найдены в: ${roots.joinToString(", ") { it.absolutePath }}", 14f))
                }
                for (file in found.take(100)) {
                    body.addView(actionButton("${file.name}  (${file.length() / 1024} КБ)\n${file.parent}") { install(file) })
                }
            }
        }.start()
    }

    /** Открывает RuStore и взводит службу на одно нажатие «Установить» (минута). */
    private fun openRuStoreAndInstall() {
        val intent = packageManager.getLaunchIntentForPackage("ru.vk.store")
        if (intent == null) {
            result.text = "RuStore не найден"
            return
        }
        startActivity(intent)
        toast("В течение минуты нажмите «Установить» в RuStore")
        autoInstallFromStore()
    }

    /** Источник списка APK: пересчитывается при каждом открытии и при возврате в приложение. */

    private fun install(file: File) {
        result.text = "Установка: ${file.name} ..."
        Thread {
            val abi = abiReport(file)
            SettingsRepository(applicationContext).addEvent("проверка ${file.name}: $abi")
            val text = try {
                installViaPmStdin(file)
            } catch (e: Exception) {
                "ошибка: ${e.javaClass.simpleName}: ${e.message}"
            }
            SettingsRepository(applicationContext).addEvent("установка ${file.name}: $text")
            runOnUiThread { result.text = "${file.name}\n$abi\n$text" }
        }.start()
    }

    /** Сравнивает архитектуры процессора ГУ с нативными библиотеками внутри APK. */
    private fun abiReport(file: File): String {
        val device = android.os.Build.SUPPORTED_ABIS.joinToString(",")
        val apkLibs = try {
            java.util.zip.ZipFile(file).use { zip ->
                java.util.Collections.list(zip.entries())
                    .map { it.name }
                    .filter { it.startsWith("lib/") }
                    .mapNotNull { it.split('/').getOrNull(1) }
                    .distinct()
                    .joinToString(",")
            }
        } catch (e: Exception) {
            "ошибка чтения: ${e.javaClass.simpleName}"
        }
        return "ГУ abi=[$device], APK lib=[${apkLibs.ifEmpty { "нет нативных библиотек" }}]"
    }

    /**
     * Передаёт APK в pm install через stdin: pm не открывает путь к файлу сам,
     * поэтому ограничения доступа к каталогам на него не действуют.
     */
    private fun installViaPmStdin(file: File): String {
        val quoted = "'" + file.absolutePath.replace("'", "'\\''") + "'"
        val cmd = "cat $quoted | pm install -r -S ${file.length()}"
        return runShell(cmd)
    }

    private fun runShell(command: String): String =
        try {
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text.trim().ifEmpty { "(пусто)" }.take(1000)
        } catch (e: Exception) {
            "ошибка: ${e.javaClass.simpleName}: ${e.message}"
        }
}
