package ru.omoda.autopilot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Озвучка ответов: TextToSpeech на канале ассистента, с фокусом (музыка приглушается). */
object LpVoice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var am: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private val pending = ArrayList<String>()

    /** true, пока идёт речь. Распознавание в это время не слушает микрофон. */
    @Volatile var speaking = false
        internal set

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Запускает движок. Все сообщения о состоянии уходят в log. */
    fun init(c: Context, log: (String) -> Unit) {
        if (tts != null) return
        am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(c.applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                failed = true
                log("голос: синтез речи не запустился (код $status)")
                return@TextToSpeech
            }
            val avail = t.isLanguageAvailable(Locale("ru"))
            log("голос: синтез запущен, русский доступен: $avail")
            if (avail < TextToSpeech.LANG_AVAILABLE) {
                failed = true
                log("голос: русского голоса нет, ответы не озвучатся")
                return@TextToSpeech
            }
            t.language = Locale("ru")
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    speaking = true
                }

                override fun onDone(id: String?) {
                    finish()
                }

                override fun onError(id: String?) {
                    finish()
                }
            })
            ready = true
            for (text in pending) say(text)
            pending.clear()
        }
    }

    fun say(text: String) {
        if (failed) return
        if (!ready) {
            pending.add(text)
            return
        }
        requestFocus()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, System.nanoTime().toString())
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT < 26) return
        val a = am ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        focus = req
        a.requestAudioFocus(req)
    }

    private fun finish() {
        speaking = false
        if (Build.VERSION.SDK_INT >= 26) {
            focus?.let { am?.abandonAudioFocusRequest(it) }
        }
        focus = null
    }

    /** Список движков синтеза, которые видит система (включая вендорские, например iFLYTEK). */
    fun logEngines(c: Context, log: (String) -> Unit) {
        try {
            val probe = TextToSpeech(c.applicationContext, null)
            val list = probe.engines.joinToString("; ") { "${it.name} (${it.label})" }
            probe.shutdown()
            log("голос: движки синтеза: ${list.ifEmpty { "нет" }}")
        } catch (e: Throwable) {
            log("голос: список движков не получен: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun close() {
        tts?.shutdown()
        tts = null
        ready = false
        speaking = false
    }
}
