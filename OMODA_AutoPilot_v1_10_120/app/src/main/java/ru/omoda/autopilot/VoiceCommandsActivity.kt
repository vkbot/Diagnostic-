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
        wakeBtn = actionButton("ИМЯ ВЫЗОВА: ${VoiceCommands.wake(this)}") { recordWake() }
        root.addView(wakeBtn)
        root.addView(actionButton("ДОБАВИТЬ КОМАНДУ") { addCommand() })
        root.addView(actionButton("ВВЕСТИ ФРАЗУ ВРУЧНУЮ") { typePhrase() })
        setContentView(ScrollView(this).also { it.setBackgroundColor(COLOR_BG); it.addView(root) })
        showList()
    }

    private fun showList() {
        container.removeAllViews()
        val commands = VoiceCommands.load(this)
        if (commands.isEmpty()) container.addView(labelView("Команд нет", 14f))
        commands.forEachIndexed { i, cmd ->
            container.addView(actionButton("«${cmd.phrase}» → ${describe(cmd)}") {
                val list = VoiceCommands.load(this)
                if (i < list.size) list.removeAt(i)
                VoiceCommands.save(this, list)
                status.text = "Удалено: «${cmd.phrase}»"
                showList()
            })
        }
    }

    private fun describe(cmd: VoiceCommand): String = when (cmd.type) {
        "app" -> try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(cmd.arg, 0)).toString()
        } catch (e: Exception) {
            cmd.arg
        }
        "wifi_on" -> "Wi-Fi включить"
        "wifi_off" -> "Wi-Fi выключить"
        else -> cmd.type
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
