package ru.omoda.autopilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Команда: фраза → действие. type: app (arg — пакет), wifi_on, wifi_off. */
data class VoiceCommand(val phrase: String, val type: String, val arg: String)

/** Хранение команд в SharedPreferences и поиск фразы в распознанном тексте. */
object VoiceCommands {
    private const val PREFS = "voice"
    private const val KEY = "commands"

    fun load(c: Context): MutableList<VoiceCommand> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return defaults()
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            VoiceCommand(o.getString("p"), o.getString("t"), o.getString("a"))
        }
    }

    fun save(c: Context, list: List<VoiceCommand>) {
        val arr = JSONArray()
        for (cmd in list) arr.put(JSONObject().put("p", cmd.phrase).put("t", cmd.type).put("a", cmd.arg))
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun defaults(): MutableList<VoiceCommand> = mutableListOf(
        VoiceCommand("карты", "app", "ru.yandex.yandexmaps"),
        VoiceCommand("музыка", "app", "ru.yandex.music"),
        VoiceCommand("вай фай включи", "wifi_on", ""),
        VoiceCommand("вай фай выключи", "wifi_off", "")
    )

    private const val WAKE_KEY = "wake"

    /** Имя вызова, после которого помощник отвечает «слушаю». По умолчанию «алиса». */
    fun wake(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(WAKE_KEY, "алиса") ?: "алиса"

    fun setWake(c: Context, word: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(WAKE_KEY, word).apply()
    }

    /** Самая длинная из подходящих фраз: «вай фай выключи» важнее «вай фай». */
    fun match(list: List<VoiceCommand>, spoken: String): VoiceCommand? {
        val t = spoken.lowercase()
        return list.filter { t.contains(it.phrase.lowercase()) }.maxByOrNull { it.phrase.length }
    }
}
