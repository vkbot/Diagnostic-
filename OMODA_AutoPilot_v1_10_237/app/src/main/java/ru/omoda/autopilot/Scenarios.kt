package ru.omoda.autopilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Профили сценариев климата. Хранятся в SharedPreferences в виде JSON. */
object Scenarios {
    private const val PREF = "scenarios"

    /** Одно условие: датчик ("out" — улица, "in" — салон), знак ("<" или ">") и порог */
    class Cond(var src: String, var op: String, var thr: Int)

    class Profile(
        val id: Long,
        var name: String,
        var enabled: Boolean,
        var op: String,
        var threshold: Int,
        var seats: Boolean,
        var seatLevel: Int,
        var hi: Boolean,
        var front: Boolean,
        var rear: Boolean,
        var steer: Boolean,
        /** Датчик условия: "out" — улица, "in" — салон (для старых профилей) */
        var src: String = "out",
        /** Все условия профиля; при логике "and" нужны все, при "or" — любое */
        var conds: MutableList<Cond> = mutableListOf(Cond(src, op, threshold)),
        var logic: String = "and"
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("name", name).put("enabled", enabled)
            .put("op", op).put("thr", threshold)
            .put("seats", seats).put("seat", seatLevel).put("seat_v", 2)
            .put("hi", hi).put("front", front).put("rear", rear).put("steer", steer)
            .put("src", src)
            .put("conds", JSONArray(conds.map { JSONObject().put("src", it.src).put("op", it.op).put("thr", it.thr) }))
            .put("logic", logic)

        companion object {
            fun fromJson(o: JSONObject): Profile {
                val pr = Profile(
                    o.getLong("id"), o.getString("name"), o.getBoolean("enabled"),
                    o.getString("op"), o.getInt("thr"),
                    // seat_v=2: уровень хранится как есть (1–3). Старые профили хранили уровень+1 — переводим
                    o.getBoolean("seats"), if (o.has("seat_v")) o.getInt("seat") else o.getInt("seat") - 1,
                    o.getBoolean("hi"), o.getBoolean("front"), o.getBoolean("rear"), o.getBoolean("steer"),
                    o.optString("src", "out")
                )
                o.optJSONArray("conds")?.let { arr ->
                    pr.conds = MutableList(arr.length()) { i ->
                        val c = arr.getJSONObject(i)
                        Cond(c.getString("src"), c.getString("op"), c.getInt("thr"))
                    }
                }
                pr.logic = o.optString("logic", "and")
                return pr
            }
        }
    }

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Задержка перед первой проверкой после запуска приложения, в секундах (ждём запуска двигателя). */
    fun launchDelaySec(c: Context): Int = p(c).getInt("launch_delay", 0)

    /** Запоминаем последнее целое значение, которое приложение записало в свойство (для строк без чтения). */
    fun noteSet(c: Context, id: Int, area: Int, v: Int) {
        p(c).edit().putInt("set_${id}_$area", v).apply()
    }

    fun lastSet(c: Context, id: Int, area: Int): Int? {
        val key = "set_${id}_$area"
        return if (p(c).contains(key)) p(c).getInt(key, 0) else null
    }

    fun setLaunchDelaySec(c: Context, v: Int) {
        p(c).edit().putInt("launch_delay", v).apply()
    }

    private var cache: MutableList<Profile>? = null

    /** Профили из памяти; с диска читаем один раз и после каждого сохранения. */
    @Synchronized
    fun all(c: Context): MutableList<Profile> = cache ?: loadAll(c).also { cache = it }

    private fun loadAll(c: Context): MutableList<Profile> {
        val raw = p(c).getString("profiles", null)
        if (raw == null) {
            val d = defaults()
            save(c, d)
            return d.toMutableList()
        }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Profile.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    @Synchronized
    fun save(c: Context, list: List<Profile>) {
        cache = list.toMutableList()
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        p(c).edit().putString("profiles", arr.toString()).apply()
    }

    fun newId(): Long = System.currentTimeMillis()

    /** Стартовые профили: выключены, пользователь включает сам. Лобовое стекло по умолчанию выключено. */
    private fun defaults(): List<Profile> = listOf(
        Profile(1L, "Холод ниже 10", false, "<", 10, true, 4, true, false, false, false),
        Profile(2L, "Очень холод ниже 2", false, "<", 2, true, 4, true, false, true, false)
    )

    fun last(c: Context): String = p(c).getString("last", "") ?: ""
    fun setLast(c: Context, v: String) = p(c).edit().putString("last", v).apply()
}
