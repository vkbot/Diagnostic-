package ru.omoda.autopilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale

/**
 * Фоновое распознавание. Ждёт имя вызова, отвечает «слушаю» и 5 секунд слушает команду.
 * Видимая обратная связь: статус в уведомлении и записи в уведомлениях главного экрана.
 * «голос выключить» останавливает службу в любой момент.
 */
class VoiceService : Service() {
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Служба", NotificationManager.IMPORTANCE_MIN))
        }
        LpVoice.init(this) { line -> say(line) }
        Thread {
            say("голос: движок по умолчанию: " + shellOut("settings get secure tts_default_synth"))
            LpVoice.logEngines(this) { line -> say(line) }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (LpLauncher.armed(this)) {
            // Скрыто: работает только восстановление
            startForeground(NOTIF_ID, note(IDLE))
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            startForeground(NOTIF_ID, note(IDLE))
            running = true
            Thread { loop() }.start()
        }
        return START_NOT_STICKY
    }

    private fun note(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (LpLauncher.armed(this)) "Служба" else text)
            .setOngoing(true)
            .build()
    }

    private fun notice(text: String) {
        if (LpLauncher.armed(this)) return // скрыто: статус голоса не обновляем
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, note(text))
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        SettingsRepository(applicationContext).addEvent(line)
    }

    private fun loop() {
        var recognizer: Recognizer? = null
        var rec: AudioRecord? = null
        try {
            val model = Model(VoiceModel.path(this) { })
            recognizer = Recognizer(model, RATE.toFloat())
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
            val buf = ByteArray(4000)
            rec.startRecording()
            var listenUntil = 0L
            var wasSpeaking = false
            while (running && !LpLauncher.armed(this)) {
                if (LpVoice.speaking) {
                    // во время ответа микрофон читаем и отбрасываем, чтобы не услышать себя
                    wasSpeaking = true
                    rec.read(buf, 0, buf.size)
                    continue
                }
                if (wasSpeaking) {
                    recognizer.reset()
                    wasSpeaking = false
                    // окно команды отсчитываем от конца фразы, а не от вызова
                    if (listenUntil != 0L) listenUntil = System.currentTimeMillis() + LISTEN_MS
                }
                if (listenUntil != 0L && System.currentTimeMillis() >= listenUntil) {
                    listenUntil = 0L
                    say("голос: время ожидания команды истекло")
                    LpIndicator.hide()
                    notice(IDLE)
                }
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0 || !recognizer.acceptWaveForm(buf, n)) continue
                val text = JSONObject(recognizer.getResult()).optString("text")
                if (text.isEmpty()) continue
                val lower = text.lowercase()
                if (isVoiceOff(lower)) {
                    say("голос: выключен голосом")
                    running = false
                    break
                }
                // эхо собственной фразы «слушаю» не команда
                if (lower.trim() in setOf("слушаю", "слушаю команду", "слушаю вас", "команду")) continue
                val wake = VoiceCommands.wake(this).lowercase()
                if (lower.contains(wake)) {
                    say("голос: вызов «$wake» услышан")
                    val rest = lower.substringAfter(wake)
                    if (VoiceCommands.match(VoiceCommands.load(this), rest) != null) {
                        execute(rest)
                        LpIndicator.show(this, "Выполняю", 1500)
                        listenUntil = 0L
                        notice(IDLE)
                    } else {
                        LpPhrases.play(this, "listening", gate = false) { line -> say(line) }
                        say("голос: слушаю команду")
                        notice("Слушаю команду...")
                        LpIndicator.show(this, "Слушаю…")
                        listenUntil = System.currentTimeMillis() + LISTEN_MS
                    }
                } else if (listenUntil != 0L) {
                    listenUntil = 0L
                    val ok = execute(text)
                    if (!ok) {
                        say("голос: не понял «$text»")
                        LpPhrases.play(this, "not_understood") { line -> say(line) }
                    }
                    LpIndicator.show(this, if (ok) "Выполняю" else "Не поняла", 1500)
                    notice(IDLE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "voice loop failed", e)
            say("голос: ошибка ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { rec?.stop() } catch (e: Exception) { }
            rec?.release()
            recognizer?.close()
            running = false
            LpIndicator.hide()
            stopSelf()
        }
    }

    private fun shellOut(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        p.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "ошибка: ${e.message}"
    }

    /** Выполняет команду по фразе. Возвращает true, если команда найдена и выполнена. */
    /** «голос выключить», «выключи голос», «отключи помощника», «помощник выключись». */
    private fun isVoiceOff(lower: String): Boolean =
        Regex("(выключ|отключ)\\S*\\s+(голос|помощник)|(голос|помощник)\\s+(выключ|отключ)").containsMatchIn(lower)

    private fun execute(spoken: String): Boolean {
        val cmd = VoiceCommands.match(VoiceCommands.load(this), spoken) ?: return false
        say("голос: выполняю «${cmd.phrase}»")
        when (cmd.type) {
            "app" -> packageManager.getLaunchIntentForPackage(cmd.arg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
            "wifi_on" -> setWifi(true)
            "wifi_off" -> setWifi(false)
        }
        val key = if (cmd.type == "wifi_on" || cmd.type == "wifi_off") cmd.type else "done"
        LpPhrases.play(this, key) { line -> say(line) }
        return true
    }

    @Suppress("DEPRECATION")
    private fun setWifi(on: Boolean) {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = on
    }

    override fun onDestroy() {
        running = false
        LpVoice.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "ru.omoda.voice.STOP"
        const val CHANNEL = "voice_min"
        const val NOTIF_ID = 78
        const val RATE = 16000
        const val LISTEN_MS = 5000L
        const val TAG = "OmodaVoice"
        const val IDLE = "Голос включён, жду вызова"
    }
}
