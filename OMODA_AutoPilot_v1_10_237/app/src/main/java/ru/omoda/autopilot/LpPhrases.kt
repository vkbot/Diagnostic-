package ru.omoda.autopilot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build

/**
 * Готовые фразы ответов: WAV в assets/voice. Синтез делается заранее на ПК (scripts/make_voice.py),
 * на ГУ только проигрывание, без интернета и без движка синтеза.
 */
object LpPhrases {
    /** Ключ → текст. Должен совпадать с PHRASES в scripts/make_voice.py. */
    val TEXTS: Map<String, String> = mapOf(
        "listening" to "Слушаю.",
        "done" to "Готово.",
        "not_understood" to "Не поняла команду.",
        "wifi_on" to "Wi-Fi включён.",
        "wifi_off" to "Wi-Fi выключен."
    )

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Хвост после окончания фразы: эхо и реверберация в салоне. */
    private const val TAIL_MS = 600L

    /** Проигрывает фразу. Если файла нет, пишет в журнал и ничего не делает. */
    /** gate=false: фраза не блокирует микрофон (подтверждение «слушаю»); её эхо фильтруется по тексту. */
    fun play(c: Context, key: String, gate: Boolean = true, log: (String) -> Unit) {
        // WAV или MP3: MediaPlayer читает оба формата
        val fd = try {
            c.assets.openFd("voice/$key.wav")
        } catch (e: Exception) {
            try {
                c.assets.openFd("voice/$key.mp3")
            } catch (e2: Exception) {
                log("голос: нет файла voice/$key.mp3 или .wav (фраза «${TEXTS[key] ?: key}»)")
                return
            }
        }
        try {
            player?.release()
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            requestFocus(am)
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.setOnCompletionListener {
                it.release()
                player = null
                abandonFocus(am)
                if (gate) handler.postDelayed({ LpVoice.speaking = false }, TAIL_MS)
            }
            mp.setOnErrorListener { p, what, extra ->
                log("голос: сбой проигрывания $key ($what/$extra)")
                try { p.release() } catch (e: Exception) { }
                player = null
                abandonFocus(am)
                LpVoice.speaking = false
                true
            }
            mp.prepare()
            player = mp
            if (gate) LpVoice.speaking = true
            mp.start()
        } catch (e: Exception) {
            LpVoice.speaking = false
            log("голос: ошибка проигрывания $key: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun requestFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT < 26) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        focus = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT < 26) return
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }
}
