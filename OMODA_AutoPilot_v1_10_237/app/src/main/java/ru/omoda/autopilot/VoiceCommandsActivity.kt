package ru.omoda.autopilot

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** Список голосовых команд. Новую фразу записываем голосом, без клавиатуры. */
class VoiceCommandsActivity : Activity() {
    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private var model: Model? = null
    private lateinit var wakeBtn: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(12), dp(16), dp(12))
        root.setBackgroundColor(COLOR_BG)
        root.addView(actionButton("НАЗАД") { finish() })
        root.addView(labelView("Команды голоса", 18f))
        status = labelView("Нажмите на команду, чтобы удалить", 14f, 4)
        root.addView(status)
        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        root.addView(container)
        wakeBtn = actionButton("ИМЯ ВЫЗОВА: ${VoiceCommands.wake(this)}") { typeWake() }
        root.addView(wakeBtn)
        root.addView(actionButton("ИМЯ ВЫЗОВА ГОЛОСОМ") { recordWake() })
        root.addView(actionButton("ДОБАВИТЬ КОМАНДУ") { addCommand() })
        root.addView(actionButton("КАТЕГОРИИ") { showCategories() })
        root.addView(actionButton("ВСЕ КОМАНДЫ") { showList() })
        root.addView(actionButton("ВВЕСТИ ФРАЗУ ВРУЧНУЮ") { typePhrase() })
        setContentView(ScrollView(this).also { it.setBackgroundColor(COLOR_BG); it.addView(root) })
        // При входе сразу категории; «ВСЕ КОМАНДЫ» открывает полный список
        showCategories()
    }

    /** Категория команды: Климат, Wi-Fi, Приложения или Прочее. */
    private fun cat(cmd: VoiceCommand): String = when (cmd.type) {
        "climate" -> "Климат"
        "wifi_on", "wifi_off" -> "Wi-Fi"
        "app" -> "Приложения"
        else -> "Прочее"
    }

    private val categories = listOf("Климат", "Wi-Fi", "Приложения", "Прочее")

    private fun removeCmd(cmd: VoiceCommand) {
        val list = VoiceCommands.load(this)
        val i = list.indexOfFirst { it == cmd }
        if (i >= 0) list.removeAt(i)
        VoiceCommands.save(this, list)
        status.text = "Удалено: «${cmd.phrase}»"
    }

    private fun commandButton(cmd: VoiceCommand, refresh: () -> Unit) =
        actionButton("«${cmd.phrase}» → ${describe(cmd)}") {
            removeCmd(cmd)
            refresh()
        }

    /** Категории: одна кнопка на категорию с количеством команд. */
    private fun showCategories() {
        container.removeAllViews()
        status.text = "Выберите категорию"
        val all = VoiceCommands.load(this)
        categories.forEach { c ->
            val n = all.count { cat(it) == c }
            container.addView(actionButton("$c ($n)") { showCategory(c) })
        }
    }

    /** Команды одной категории, по фразе. Для климата — кнопка добавления через группы. */
    private fun showCategory(c: String) {
        container.removeAllViews()
        status.text = "Категория: $c. Нажмите на команду, чтобы удалить"
        val items = VoiceCommands.load(this).filter { cat(it) == c }.sortedBy { it.phrase }
        if (items.isEmpty()) container.addView(labelView("Команд нет", 14f))
        items.forEach { cmd -> container.addView(commandButton(cmd) { showCategory(c) }) }
        if (c == "Климат") container.addView(actionButton("ДОБАВИТЬ КОМАНДУ КЛИМАТА") { showClimatePicker() })
        container.addView(actionButton("КАТЕГОРИИ") { showCategories() })
    }

    /** Все команды, сгруппированные по категориям. */
    private fun showList() {
        container.removeAllViews()
        status.text = "Все команды по категориям. Нажмите на команду, чтобы удалить"
        val all = VoiceCommands.load(this)
        if (all.isEmpty()) container.addView(labelView("Команд нет", 14f))
        categories.forEach { c ->
            val items = all.filter { cat(it) == c }.sortedBy { it.phrase }
            if (items.isEmpty()) return@forEach
            container.addView(labelView("$c (${items.size})", 16f))
            items.forEach { cmd -> container.addView(commandButton(cmd) { showList() }) }
        }
    }

    /** Группы команд климата: сначала выбираем группу, потом команду. */
    private fun showClimatePicker() {
        container.removeAllViews()
        status.text = "Выберите группу команд климата"
        VoiceClimate.groups().forEach { g ->
            val n = VoiceClimate.actions.count { VoiceClimate.group(it.key) == g }
            container.addView(actionButton("$g ($n)") { showClimateGroup(g) })
        }
        container.addView(actionButton("КАТЕГОРИИ") { showCategories() })
    }

    private fun showClimateGroup(g: String) {
        container.removeAllViews()
        status.text = "$g: нажмите команду, чтобы добавить с фразой по умолчанию"
        VoiceClimate.actions.filter { VoiceClimate.group(it.key) == g }.forEach { a ->
            container.addView(actionButton("${a.title}\n«${a.phrase}»") {
                val list = VoiceCommands.load(this)
                list.add(VoiceCommand(a.phrase, "climate", a.key))
                VoiceCommands.save(this, list)
                status.text = "Добавлено: «${a.phrase}» → ${a.title}"
            })
        }
        container.addView(actionButton("ГРУППЫ КЛИМАТА") { showClimatePicker() })
    }

    private fun describe(cmd: VoiceCommand): String = when (cmd.type) {
        "app" -> try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(cmd.arg, 0)).toString()
        } catch (e: Exception) {
            cmd.arg
        }
        "wifi_on" -> "Wi-Fi включить"
        "wifi_off" -> "Wi-Fi выключить"
        "climate" -> "Климат: " + VoiceClimate.title(cmd.arg)
        else -> cmd.type
    }

    /** Имя вызова вводится вручную. */
    private fun typeWake() {
        val edit = android.widget.EditText(this).apply {
            setText(VoiceCommands.wake(this@VoiceCommandsActivity))
            hint = "имя вызова"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Имя вызова помощника")
            .setView(edit)
            .setPositiveButton("Сохранить") { _, _ ->
                val w = edit.text.toString().trim().lowercase()
                if (w.isNotEmpty()) {
                    VoiceCommands.setWake(this, w)
                    wakeBtn.text = "ИМЯ ВЫЗОВА: $w"
                    status.text = "Имя вызова: «$w»"
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Имя вызова записывается голосом: скажите его, например «алиса». */
    private fun recordWake() {
        status.text = "Скажите имя вызова, 4 секунды..."
        Thread {
            try {
                val word = recognizePhrase()
                runOnUiThread {
                    if (word.isEmpty()) {
                        status.text = "Не расслышал, попробуйте ещё раз"
                    } else {
                        VoiceCommands.setWake(this, word)
                        wakeBtn.text = "ИМЯ ВЫЗОВА: $word"
                        status.text = "Имя вызова: «$word»"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка: ${e.javaClass.simpleName}: ${e.message}" }
            }
        }.start()
    }

    private fun addCommand() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            status.text = "Разрешите микрофон и нажмите ещё раз"
            return
        }
        status.text = "Скажите фразу, 4 секунды..."
        Thread {
            try {
                val phrase = recognizePhrase()
                runOnUiThread {
                    if (phrase.isEmpty()) status.text = "Не расслышал, попробуйте ещё раз"
                    else chooseAction(phrase)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка: ${e.javaClass.simpleName}: ${e.message}" }
            }
        }.start()
    }

    /** Записывает 4 секунды и возвращает распознанный текст. */
    private fun recognizePhrase(): String {
        val m = model ?: Model(VoiceModel.path(this) { msg -> runOnUiThread { status.text = msg } }).also { model = it }
        val recognizer = Recognizer(m, VoiceService.RATE.toFloat())
        var rec: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(VoiceService.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, VoiceService.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
            val buf = ByteArray(4000)
            val until = System.currentTimeMillis() + 4000
            rec.startRecording()
            while (System.currentTimeMillis() < until) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) recognizer.acceptWaveForm(buf, n)
            }
            return JSONObject(recognizer.getFinalResult()).optString("text")
        } finally {
            try { rec?.stop() } catch (e: Exception) { }
            rec?.release()
            recognizer.close()
        }
    }

    /** Ручной ввод фразы: на случай, когда голосовая запись неудобна. */
    private fun typePhrase() {
        container.removeAllViews()
        status.text = "Введите фразу и нажмите «ДАЛЬШЕ»"
        val input = EditText(this)
        input.hint = "например: открой карты"
        input.setTextColor(COLOR_TEXT)
        input.setHintTextColor(COLOR_STROKE)
        container.addView(input)
        container.addView(actionButton("ДАЛЬШЕ") {
            val phrase = input.text.toString().trim()
            if (phrase.isEmpty()) status.text = "Фраза пустая"
            else chooseAction(phrase)
        })
        container.addView(actionButton("Отмена") { showList() })
    }

    private fun chooseAction(phrase: String) {
        status.text = "Фраза: «$phrase». Что сделать?"
        container.removeAllViews()
        container.addView(actionButton("Открыть приложение") { pickApp(phrase) })
        container.addView(actionButton("Wi-Fi включить") { save(VoiceCommand(phrase, "wifi_on", "")) })
        container.addView(actionButton("Wi-Fi выключить") { save(VoiceCommand(phrase, "wifi_off", "")) })
        container.addView(actionButton("Отмена") { showList() })
    }

    private fun pickApp(phrase: String) {
        status.text = "Выберите приложение для «$phrase»"
        container.removeAllViews()
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { Pair(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.first.lowercase() }
        for ((label, pkg) in apps) {
            val btn = actionButton(label) { save(VoiceCommand(phrase, "app", pkg)) }
            try {
                val icon = pm.getApplicationIcon(pkg)
                icon.setBounds(0, 0, dp(40), dp(40))
                btn.setCompoundDrawables(icon, null, null, null)
                btn.compoundDrawablePadding = dp(12)
            } catch (e: Exception) {
                // без иконки кнопка всё равно работает
            }
            container.addView(btn)
        }
        container.addView(actionButton("Отмена") { showList() })
    }

    private fun save(cmd: VoiceCommand) {
        val list = VoiceCommands.load(this)
        list.add(cmd)
        VoiceCommands.save(this, list)
        status.text = "Сохранено: «${cmd.phrase}»"
        showList()
    }

    override fun onDestroy() {
        model?.close()
        model = null
        super.onDestroy()
    }
}
