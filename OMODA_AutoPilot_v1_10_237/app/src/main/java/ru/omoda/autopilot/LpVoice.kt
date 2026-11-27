package ru.omoda.autopilot

import android.content.Context
import android.speech.tts.TextToSpeech

/**
 * Состояние голоса для распознавания и диагностика движков синтеза.
 * Ответы озвучиваются готовыми файлами (LpPhrases), синтез речи не используется.
 */
object LpVoice {
    /** true, пока идёт проигрывание ответа. Распознавание в это время не слушает микрофон. */
    @Volatile var speaking = false
        internal set

    /** Список движков синтеза, которые видит система (включая вендорские, например iFLYTEK). Только для журнала. */
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

    /** Сбрасывает состояние при остановке службы голоса. */
    fun close() {
        speaking = false
    }
}
