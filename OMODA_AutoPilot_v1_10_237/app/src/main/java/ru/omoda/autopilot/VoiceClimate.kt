package ru.omoda.autopilot

/**
 * Голосовые команды климата. Выключение руля, стекла, A/C и SYNC проверено на ГУ (зона 0x400).
 * Остальные значения взяты из тестов климата; где выключение не проверено, в названии есть пометка.
 */
object VoiceClimate {
    class Action(
        val key: String,
        val title: String,
        val phrase: String,
        val min: Double = 0.0,
        val max: Double = 0.0,
        val needsNumber: Boolean = false,
        val run: (Double?) -> String
    )

    private fun res(err: String?, ok: String) = if (err == null) ok else "не сработало: $err"

    private fun temp(v: Float): String =
        res(CarProps.set(CarIds.TEMP_LEFT, 0, v) ?: CarProps.set(CarIds.TEMP_RIGHT, 64, v), "температура $v")

    private fun stepTemp(d: Double): String {
        val cur = (CarProps.get(CarIds.TEMP_LEFT, 0) as? Number)?.toDouble() ?: return "нет показания температуры"
        return temp((cur + d).coerceIn(16.0, 28.0).toFloat())
    }

    private fun seat(id: Int, area: Int, n: Double?, name: String): String {
        val v = if (n == null || n == 0.0) 1 else n.toInt() + 1
        return res(CarProps.set(id, area, v), "$name уровень ${n?.toInt() ?: 0}")
    }

    private fun one(id: Int, area: Int, v: Any, ok: String) = res(CarProps.set(id, area, v), ok)

    val actions: List<Action> = listOf(
        Action("temp", "Температура, °C (16–28, шаг 0,5)", "температура", 16.0, 28.0, true) { n ->
            temp((n ?: 22.0).toFloat())
        },
        Action("temp_up", "Температура выше на 0,5", "температура выше") { _ -> stepTemp(0.5) },
        Action("temp_down", "Температура ниже на 0,5", "температура ниже") { _ -> stepTemp(-0.5) },
        Action("hi", "HI 30,5 (обе стороны)", "хай") { _ ->
            res(CarProps.set(CarIds.TEMP_LEFT, 0, 30.5f) ?: CarProps.set(CarIds.TEMP_RIGHT, 64, 30.5f), "HI 30,5")
        },
        Action("lo", "LO 15,5 (обе стороны)", "лоу") { _ ->
            res(CarProps.set(CarIds.TEMP_LEFT, 0, 15.5f) ?: CarProps.set(CarIds.TEMP_RIGHT, 64, 15.5f), "LO 15,5")
        },
        Action("fan", "Скорость обдува (0–10)", "обдув", 0.0, 10.0, true) { n ->
            one(CarIds.FAN_SPEED, 80, (n ?: 4.0).toInt(), "обдув ${n?.toInt()}")
        },
        Action("dir_up", "Направление: верх", "направление верх") { _ -> one(CarIds.FAN_DIRECTION, 80, 1, "направление верх") },
        Action("dir_down", "Направление: низ", "направление низ") { _ -> one(CarIds.FAN_DIRECTION, 80, 2, "направление низ") },
        Action("dir_both", "Направление: верх и низ", "направление верх низ") { _ -> one(CarIds.FAN_DIRECTION, 80, 3, "верх и низ") },
        Action("dir_glass", "Направление: стекло", "направление стекло") { _ -> one(CarIds.FAN_DIRECTION, 80, 4, "направление стекло") },
        Action("seat_drv", "Подогрев водителя (0–3)", "подогрев водителя", 0.0, 3.0, true) { n ->
            seat(CarIds.SEAT_HEAT_DRV, 16, n, "подогрев водителя")
        },
        Action("seat_pass", "Подогрев пассажира (0–3)", "подогрев пассажира", 0.0, 3.0, true) { n ->
            seat(CarIds.SEAT_HEAT_PASS, 64, n, "подогрев пассажира")
        },
        Action("seat_rl", "Подогрев задний левый (0–3)", "подогрев задний левый", 0.0, 3.0, true) { n ->
            seat(CarIds.SEAT_HEAT_RL, 256, n, "задний левый")
        },
        Action("seat_rr", "Подогрев задний правый (0–3)", "подогрев задний правый", 0.0, 3.0, true) { n ->
            seat(CarIds.SEAT_HEAT_RR, 1024, n, "задний правый")
        },
        Action("vent_drv", "Вентиляция водителя (0–2; выкл не проверено)", "вентиляция водителя", 0.0, 2.0, true) { n ->
            val v = if (n == null || n == 0.0) 1 else n.toInt() + 1
            one(CarIds.SEAT_VENT_DRV, 16, v, "вентиляция водителя ${n?.toInt() ?: 0}")
        },
        Action("ac_on", "Кондиционер включить", "кондиционер включи") { _ -> one(CarIds.AC, 1024, 1, "кондиционер вкл") },
        Action("ac_off", "Кондиционер выключить", "кондиционер выключи") { _ -> one(CarIds.AC, 1024, 2, "кондиционер выкл") },
        Action("sync_on", "SYNC включить", "синхронизация включи") { _ -> one(CarIds.SYNC, 1024, 1, "SYNC вкл") },
        Action("sync_off", "SYNC выключить", "синхронизация выключи") { _ -> one(CarIds.SYNC, 1024, 2, "SYNC выкл") },
        Action("rear_on", "Заднее стекло включить", "заднее стекло включи") { _ -> one(CarIds.REAR_DEFROST, 1024, 1, "заднее стекло вкл") },
        Action("rear_off", "Заднее стекло выключить", "заднее стекло выключи") { _ -> one(CarIds.REAR_DEFROST, 1024, 3, "заднее стекло выкл") },
        Action("steer_on", "Обогрев руля включить", "руль подогрев включи") { _ -> one(CarIds.STEER_HEAT, 1024, 1, "руль вкл") },
        Action("steer_off", "Обогрев руля выключить", "руль подогрев выключи") { _ -> one(CarIds.STEER_HEAT, 1024, 3, "руль выкл") },
        Action("front_on", "Лобовое (обдув, не подтверждено)", "лобовое включи") { _ -> one(CarIds.FRONT_DEFROST, 0, 1, "лобовое вкл") },
        Action("recirc_in", "Рециркуляция внутренняя", "рециркуляция внутренняя") { _ -> one(CarIds.RECIRC, 0, 1, "рециркуляция внутр") },
        Action("recirc_out", "Рециркуляция наружная", "рециркуляция наружная") { _ -> one(CarIds.RECIRC, 0, 2, "рециркуляция наруж") },
        Action("auto_on", "Авто климат включить", "авто климат") { _ -> one(CarIds.AUTO_AC, 80, 1, "авто климат вкл") }
    )

    private val groupOf = mapOf(
        "temp" to "Температура", "temp_up" to "Температура", "temp_down" to "Температура",
        "hi" to "Температура", "lo" to "Температура",
        "fan" to "Обдув", "dir_up" to "Обдув", "dir_down" to "Обдув", "dir_both" to "Обдув", "dir_glass" to "Обдув",
        "seat_drv" to "Подогревы и вентиляция", "seat_pass" to "Подогревы и вентиляция",
        "seat_rl" to "Подогревы и вентиляция", "seat_rr" to "Подогревы и вентиляция",
        "vent_drv" to "Подогревы и вентиляция",
        "ac_on" to "Климат", "ac_off" to "Климат", "sync_on" to "Климат", "sync_off" to "Климат",
        "recirc_in" to "Климат", "recirc_out" to "Климат", "auto_on" to "Климат",
        "rear_on" to "Стёкла и руль", "rear_off" to "Стёкла и руль", "steer_on" to "Стёкла и руль",
        "steer_off" to "Стёкла и руль", "front_on" to "Стёкла и руль"
    )

    fun group(key: String): String = groupOf[key] ?: "Прочее"

    fun groups(): List<String> = listOf("Температура", "Обдув", "Подогревы и вентиляция", "Климат", "Стёкла и руль")

    fun title(key: String): String = actions.find { it.key == key }?.title ?: key

    private val words = mapOf(
        "ноль" to 0.0, "один" to 1.0, "одна" to 1.0, "два" to 2.0, "две" to 2.0, "три" to 3.0,
        "четыре" to 4.0, "пять" to 5.0, "шесть" to 6.0, "семь" to 7.0, "восемь" to 8.0, "девять" to 9.0,
        "десять" to 10.0, "одиннадцать" to 11.0, "двенадцать" to 12.0, "тринадцать" to 13.0,
        "четырнадцать" to 14.0, "пятнадцать" to 15.0, "шестнадцать" to 16.0, "семнадцать" to 17.0,
        "восемнадцать" to 18.0, "девятнадцать" to 19.0, "двадцать" to 20.0, "тридцать" to 30.0
    )

    /** Число из фразы: цифры или слова («двадцать два с половиной» = 22.5). */
    fun parseNumber(s: String): Double? {
        val t = s.lowercase().replace(',', '.')
        Regex("\\d+(\\.\\d+)?").find(t)?.let { return it.value.toDouble() }
        var sum = 0.0
        var found = false
        for (w in t.split(Regex("\\s+"))) {
            words[w]?.let { sum += it; found = true }
        }
        if (!found) return null
        if (t.contains("половин")) sum += 0.5
        return sum
    }

    /** Выполняет команду климата. Запись идёт в фоне; результат передаётся в log. */
    fun execute(cmd: VoiceCommand, spoken: String, log: (String) -> Unit) {
        val act = actions.find { it.key == cmd.arg } ?: return log("климат: неизвестная команда")
        val rest = VoiceCommands.restAfter(spoken, cmd.phrase)
        val n = if (act.needsNumber) parseNumber(rest) else null
        if (act.needsNumber && (n == null || n < act.min || n > act.max)) {
            return log("климат: скажите число от ${act.min} до ${act.max}")
        }
        Thread { log("климат: ${act.run(n)}") }.start()
    }
}
