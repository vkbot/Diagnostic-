package ru.omoda.autopilot

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.Executors

/** Климат для ежедневного использования: только подтверждённые на машине функции. */
class ClimateActivity : Activity() {
    private val pollBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var msgView: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val vals = HashMap<String, Any>()
    private val views = HashMap<String, TextView>()
    private val lastWrite = HashMap<String, Long>()
    /**
     * Запасное чтение для передних сидений: основной ID по зоне не отдаёт значение (статус 3).
     * Пробуем ID уровней сидений из SDK по нескольким зонам; первое ответившее значение — текущий уровень.
     */
    private fun alternateSeatRead(r: Row): Any? {
        // Штатное приложение показывает передние сиденья по стандартному HVAC_SEAT_TEMPERATURE.
        // Зоны: SEAT_ROW_1_LEFT = 1 (водитель), SEAT_ROW_1_RIGHT = 4 (пассажир) — из того же файла.
        return when (r.key) {
            "seatDrv" -> CarProps.get(CarIds.HVAC_SEAT_TEMPERATURE, 1)
            "seatPass" -> CarProps.get(CarIds.HVAC_SEAT_TEMPERATURE, 4)
            else -> null
        }
    }

    /** Последнее значение, которое приложение записало в свойство: id/зона -> значение */
    private val lastSet = HashMap<String, Any>()

    private enum class Kind { TEMP, INT, LEVELS, DIR, SENSOR, TOGGLE }

    private class Row(
        val key: String,
        val title: String,
        val id: Int,
        val area: Int,
        val kind: Kind,
        val step: Double = 0.0,
        val min: Double = 0.0,
        val max: Double = 0.0,
        val levels: List<Int> = emptyList(),
        val names: Map<Int, String> = emptyMap(),
        /** Для TOGGLE: значение записи «вкл», «выкл» и значение чтения, при котором включено */
        val onV: Int = 0,
        val offV: Int = 0,
        val onRead: Int = 1
    )

    private class Fixed(val label: String, val id: Int, val area: Int, val value: Any)

    // выключение у машины — значение 1 (0 она игнорирует), уровни 1–3 — значения 2, 3, 4
    // Передние сиденья и вентиляция (по проверке на машине): 1 выкл, 2–4 уровни 1–3
    private val frontLevels = listOf(1, 2, 3, 4)
    private val frontNames = mapOf(1 to "выкл", 2 to "уровень 1", 3 to "уровень 2", 4 to "уровень 3")
    // Задние сиденья (по проверке на машине): 0 выкл, 1–3 уровни 1–3
    private val rearLevels = listOf(0, 1, 2, 3)
    private val rearNames = mapOf(0 to "выкл", 1 to "уровень 1", 2 to "уровень 2", 3 to "уровень 3")
    // Чтение задних сидений: значение совпадает с уровнем (raw 3 = уровень 3, по проверке на машине). Запись не меняется.
    private val rearReadNames = mapOf(0 to "выкл", 1 to "уровень 1", 2 to "уровень 2", 3 to "уровень 3")
    private val dirNames = mapOf(0 to "выкл", 1 to "верх", 2 to "низ", 3 to "верх+низ", 4 to "стекло")

    private val rows = listOf(
        Row("left", "Температура слева", CarIds.TEMP_LEFT, 0, Kind.TEMP, 0.5, 16.0, 28.0),
        Row("right", "Температура справа", CarIds.TEMP_RIGHT, 64, Kind.TEMP, 0.5, 16.0, 28.0),
        Row("fan", "Скорость обдува", CarIds.FAN_SPEED, 80, Kind.INT, 1.0, 0.0, 10.0),
        Row("dir", "Направление обдува", CarIds.FAN_DIRECTION, 80, Kind.DIR, names = dirNames),
        Row("seatDrv", "Подогрев водителя", CarIds.SEAT_HEAT_DRV, 16, Kind.LEVELS, levels = frontLevels, names = frontNames),
        Row("seatPass", "Подогрев переднего пассажира", CarIds.SEAT_HEAT_PASS, 64, Kind.LEVELS, levels = frontLevels, names = frontNames),
        Row("seatRL", "Подогрев задний левый", CarIds.SEAT_HEAT_RL, 256, Kind.LEVELS, levels = frontLevels, names = frontNames),
        Row("seatRR", "Подогрев задний правый", CarIds.SEAT_HEAT_RR, 1024, Kind.LEVELS, levels = frontLevels, names = frontNames),
        Row("ventDrv", "Вентиляция водителя", CarIds.SEAT_VENT_DRV, 16, Kind.LEVELS, levels = frontLevels, names = frontNames),
        Row("ventPass", "Вентиляция переднего пассажира", CarIds.SEAT_VENT_PASS, 64, Kind.LEVELS, levels = frontLevels, names = frontNames),
        // Выключение найдено на ГУ (зона 0x400): руль 3, стекло 3, A/C 2, SYNC 2
        Row("steer", "Обогрев руля", CarIds.STEER_HEAT, 1024, Kind.TOGGLE, onV = 1, offV = 3, onRead = 1),
        Row("rear", "Заднее стекло", CarIds.REAR_DEFROST, 1024, Kind.TOGGLE, onV = 1, offV = 3, onRead = 1),
        Row("ac", "Кондиционер A/C", CarIds.AC, 1024, Kind.TOGGLE, onV = 1, offV = 2, onRead = 1),
        Row("sync", "SYNC", CarIds.SYNC, 1024, Kind.TOGGLE, onV = 1, offV = 2, onRead = 0),
        Row("outside", "Температура улицы", CarIds.SENSOR_OUTSIDE, 0, Kind.SENSOR),
        Row("inside", "Температура салона", CarIds.SENSOR_INSIDE, 0, Kind.SENSOR)
    )

    private val fixed = listOf(
        Fixed("HI слева", CarIds.TEMP_LEFT, 0, 30.5f),
        Fixed("LO слева", CarIds.TEMP_LEFT, 0, 15.5f),
        Fixed("HI справа", CarIds.TEMP_RIGHT, 64, 30.5f),
        Fixed("LO справа", CarIds.TEMP_RIGHT, 64, 15.5f),
        Fixed("Авто климат", CarIds.AUTO_AC, 80, 1),
        Fixed("Рецирк внутр", CarIds.RECIRC, 0, 1),
        Fixed("Рецирк наруж", CarIds.RECIRC, 0, 2),
    )

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (pollBusy.getAndSet(true)) {
                ui.postDelayed(this, 2000)
                return
            }
            worker.execute {
                try {
                    for (r in rows) {
                        val nv = CarProps.get(r.id, r.area) ?: alternateSeatRead(r)
                        runOnUiThread {
                            // сразу после нажатия не даём опросу вернуть старое значение
                            val recent = System.currentTimeMillis() - (lastWrite[r.key] ?: 0L) < 3000
                            if (!recent) {
                                // неудачное чтение не стирает последнее хорошее значение
                                if (nv != null) vals[r.key] = nv
                            }
                            render(r)
                        }
                    }
                } catch (e: Throwable) {
                    // ошибка чтения не должна останавливать опрос
                } finally {
                    pollBusy.set(false)
                }
            }
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply { text = "Климат"; textSize = 22f })
        msgView = TextView(this).apply { textSize = 15f; text = "" }
        root.addView(msgView)

        for (r in rows) {
            val label = TextView(this).apply { textSize = 17f; text = "${r.title}: —" }
            views[r.key] = label
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            when (r.kind) {
                Kind.TEMP, Kind.INT, Kind.LEVELS -> {
                    line.addView(button("−") { change(r, -1) })
                    line.addView(label)
                    line.addView(button("+") { change(r, 1) })
                }
                Kind.DIR -> {
                    line.addView(label)
                    listOf(1, 2, 3, 4).forEach { v ->
                        line.addView(button(dirNames[v] ?: v.toString()) { write(r.id, r.area, v, r.title) })
                    }
                }
                Kind.SENSOR -> line.addView(label)
                Kind.TOGGLE -> {
                    line.addView(label)
                    // Две явные кнопки: состояние не угадываем, значит нажатие «выкл» всегда выключает
                    line.addView(button("вкл") { setRow(r, r.onV) })
                    line.addView(button("выкл") { setRow(r, r.offV) })
                }
            }
            root.addView(line)
        }

        root.addView(TextView(this).apply { textSize = 17f; text = "Быстрые кнопки:" })
        fixed.chunked(3).forEach { chunk ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (f in chunk) line.addView(button(f.label) { write(f.id, f.area, f.value, f.label) })
            root.addView(line)
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Запись во все зоны по очереди с логом в файл. */
    private fun writeAll(id: Int, value: Int, label: String) {
        worker.execute {
            CarProps.writeZones(id, value) { ClimateLog.line("$label: $it") }
            ClimateLog.line("$label: готово")
            runOnUiThread { msgView?.text = "$label: готово, см. omoda_climate_log.txt" }
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            setOnClickListener { onClick() }
        }

    override fun onResume() {
        super.onResume()
        ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun render(r: Row) {
        val v = vals[r.key]
        // Машина не отдаёт чтение по этой строке: показываем то, что приложение задало последним
        val noValue = if (v == null) {
            // Берём последнее заданное значение: с экрана климата или из сценария
            val set: Number? = (lastSet["${r.id}/${r.area}"] as? Number)
                ?: Scenarios.lastSet(this, r.id, r.area)?.let { it as Number }
            set?.let { n ->
                "задано: ${r.names[n.toInt()] ?: n} (чтение недоступно)"
            } ?: "нет данных"
        } else null
        val text = noValue ?: when (r.kind) {
            Kind.TEMP, Kind.SENSOR -> if (v == null) "—" else String.format(Locale.US, "%.1f °C", (v as Number).toDouble())
            Kind.INT -> v?.toString() ?: "—"
            Kind.DIR -> if (v == null) "—" else r.names[(v as Number).toInt()] ?: v.toString()
            // уровни показываем с сырым значением, чтобы сверять с машиной
            Kind.LEVELS -> if (v == null) "—" else {
                val names = if (r.key == "seatRL" || r.key == "seatRR") rearReadNames else r.names
                "${names[(v as Number).toInt()] ?: v.toString()} [raw ${v}]"
            }
            Kind.TOGGLE -> if (v == null) "—" else if ((v as Number).toInt() == r.onRead) "вкл" else "выкл"
        }
        views[r.key]?.text = "${r.title}: $text"
    }

    /** Шаг по температуре, скорости или уровню подогрева/вентиляции. */
    private fun change(r: Row, sign: Int) {
        val cur = vals[r.key]
        val v: Any = when (r.kind) {
            Kind.LEVELS -> {
                // следующий уровень по значению. Раньше значение «выкл» (0 у задних) принудительно становилось 1,
                // и первое нажатие давало уровень 2. Неизвестное состояние начинаем с первого значения списка.
                val sorted = r.levels.sorted()
                val c = (cur as? Number)?.toInt() ?: sorted.first()
                if (sign > 0) sorted.firstOrNull { it > c } ?: sorted.last()
                else sorted.lastOrNull { it < c } ?: sorted.first()
            }
            Kind.TEMP -> ((cur as? Number)?.toDouble() ?: 22.0)
                .let { (it + sign * r.step).coerceIn(r.min, r.max).toFloat() }
            Kind.INT -> ((cur as? Number)?.toDouble() ?: 4.0)
                .let { (it + sign * r.step).coerceIn(r.min, r.max).toInt() }
            Kind.DIR, Kind.SENSOR, Kind.TOGGLE -> return
        }
        vals[r.key] = v
        lastWrite[r.key] = System.currentTimeMillis()
        render(r)
        write(r.id, r.area, v, r.title)
    }

    /** Переключение по текущему состоянию: если включено — пишем «выкл», иначе «вкл». */
    private fun setRow(r: Row, target: Int) {
        // Задние сиденья: записанное значение на единицу больше показания уровня (запись 3 — уровень 2, читается 2).
        // Сразу показываем ожидаемое показание, а не записанное число, иначе на 3 секунды мелькает неверный уровень.
        val shown = if (r.kind == Kind.LEVELS && (r.key == "seatRL" || r.key == "seatRR")) (target - 1).coerceAtLeast(0) else target
        // сразу показываем новое состояние и не даём опросу вернуть старое на 3 секунды
        vals[r.key] = shown
        lastWrite[r.key] = System.currentTimeMillis()
        render(r)
        if (r.kind == Kind.TOGGLE && (r.id == CarIds.STEER_HEAT || r.id == CarIds.REAR_DEFROST)) {
            // Руль и заднее стекло: по тесту выключение идёт 2, потом 3 по всем зонам; одна попытка, машина отвечает с задержкой
            worker.execute {
                val wantOn = target == r.onV
                if (!wantOn) {
                    CarProps.writeZones(r.id, 2) { }
                    Thread.sleep(300)
                }
                CarProps.writeZones(r.id, target) { }
                Thread.sleep(700)
                val now = (CarProps.get(r.id, r.area) as? Number)?.toInt()
                val ok = if (wantOn) now == r.onRead else now != null && now != r.onRead
                runOnUiThread {
                    msgView?.text = if (ok) "${r.title}: ${if (wantOn) "включено" else "выключено"}"
                        else "${r.title}: пока не ${if (wantOn) "включилось" else "выключилось"} (машина отвечает с задержкой)"
                    vals[r.key] = now ?: target
                    render(r)
                }
            }
        } else {
            write(r.id, r.area, target, r.title)
        }
    }

    private fun write(id: Int, area: Int, v: Any, label: String = "") {
        worker.execute {
            val err = CarProps.set(id, area, v)
            // Сразу читаем обратно: видно, что машина приняла, а не только что запись не упала
            val back = if (err == null) CarProps.get(id, area) else null
            runOnUiThread {
                if (err == null) lastSet["$id/$area"] = v
                msgView?.text = if (err == null) "записано $label: $v, прочитано ${back ?: "нет"}" else "не ушло: $label ($err)"
            }
        }
    }
}
