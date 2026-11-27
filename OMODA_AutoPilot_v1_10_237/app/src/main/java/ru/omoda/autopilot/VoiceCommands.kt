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
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return withClimate(c, defaults())
        val arr = JSONArray(raw)
        val list = MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            VoiceCommand(o.getString("p"), o.getString("t"), o.getString("a"))
        }
        return withClimate(c, list)
    }

    /** Команды климата добавляются один раз, если их ещё нет; потом пользователь управляет ими сам. */
    private fun withClimate(c: Context, list: MutableList<VoiceCommand>): MutableList<VoiceCommand> {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("climate_seeded", false)) return list
        for (a in VoiceClimate.actions) {
            if (list.none { it.type == "climate" && it.arg == a.key }) list.add(VoiceCommand(a.phrase, "climate", a.key))
        }
        save(c, list)
        prefs.edit().putBoolean("climate_seeded", true).apply()
        return list
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
        return list.filter { findForm(t, it.phrase) != null }.maxByOrNull { it.phrase.length }
    }

    /** Фраза и её основа без окончания: «температура» находит и «температуру», «температуре». */
    private fun stemOf(phrase: String): String {
        val p = phrase.lowercase()
        return if (p.length >= 6 && p.last() in "аеиоуыяю") p.dropLast(1) else p
    }

    /** Позиция и длина найденной формы фразы в тексте, или null. */
    fun findForm(spoken: String, phrase: String): IntRange? {
        val s = spoken.lowercase()
        val p = phrase.lowercase()
        val i = s.indexOf(p)
        if (i >= 0) return i until i + p.length
        val stem = stemOf(p)
        val j = s.indexOf(stem)
        return if (j >= 0) j until j + stem.length else null
    }

    /** Текст после найденной фразы (любой её формы). */
    fun restAfter(spoken: String, phrase: String): String {
        val r = findForm(spoken, phrase) ?: return ""
        return spoken.lowercase().substring(r.last + 1)
    }
}
