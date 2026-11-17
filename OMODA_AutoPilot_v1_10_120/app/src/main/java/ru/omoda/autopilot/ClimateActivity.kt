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
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.Executors

/** Ручное управление климатом. Значения читаются с машины раз в две секунды. Записи идут сразу. */
class ClimateActivity : Activity() {
    private val apk = "/system/priv-app/CarService/CarService.apk"
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var link: Link? = null
    private val vals = HashMap<String, Any>()
    private val views = HashMap<String, TextView>()
    private var scanView: TextView? = null

    private class Link(
        val prop: Any,
        val getProp: Method,
        val setProp: Method,
        val cpvCtor: Constructor<*>,
        val getValue: Method,
        val getStatus: Method
    )

    private enum class Kind { TEMP, INT, LEVELS, DIR, SENSOR }

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
        val names: Map<Int, String> = emptyMap()
    )

    private class Fixed(val label: String, val id: Int, val area: Int, val value: Any)

    private val seatLevels = listOf(0, 2, 3, 4)
    private val seatNames = mapOf(0 to "выкл", 2 to "уровень 1", 3 to "уровень 2", 4 to "уровень 3")
    private val dirNames = mapOf(
        0 to "выкл", 1 to "верх", 2 to "низ", 3 to "верх+низ",
        4 to "стекло", 6 to "низ+стекло", 7 to "все", 8 to "верх+стекло"
    )

    private val rows = listOf(
        Row("left", "Температура слева", 33554551, 0, Kind.TEMP, 0.5, 16.0, 28.0),
        Row("right", "Температура справа", 33554552, 64, Kind.TEMP, 0.5, 16.0, 28.0),
        Row("fan", "Скорость обдува", 33554448, 80, Kind.INT, 1.0, 0.0, 7.0),
        Row("dir", "Направление обдува", 33554449, 80, Kind.DIR, names = dirNames),
        Row("seatDrv", "Подогрев водителя", 33554453, 16, Kind.LEVELS, levels = seatLevels, names = seatNames),
        Row("seatPass", "Подогрев переднего пассажира", 33554569, 64, Kind.LEVELS, levels = seatLevels, names = seatNames),
        Row("seatRL", "Подогрев задний левый", 33554570, 256, Kind.LEVELS, levels = seatLevels, names = seatNames),
        Row("seatRR", "Подогрев задний правый", 33554572, 1024, Kind.LEVELS, levels = seatLevels, names = seatNames),
        Row("outside", "Температура улицы", 33554496, 0, Kind.SENSOR),
        Row("inside", "Температура салона", 33554497, 0, Kind.SENSOR)
    )

    private val fixed = listOf(
        Fixed("HI слева", 33554551, 0, 28.0f),
        Fixed("LO слева", 33554551, 0, 16.0f),
        Fixed("HI справа", 33554552, 64, 28.0f),
        Fixed("LO справа", 33554552, 64, 16.0f),
        Fixed("Авто обдув", 33554463, 80, 1),
        Fixed("Авто климат", 33554435, 80, 1),
        Fixed("SYNC вкл", 33554436, 0, 1),
        Fixed("SYNC выкл", 33554436, 0, 0),
        Fixed("A/C вкл", 33554498, 0, 1),
        Fixed("A/C выкл", 33554498, 0, 0)
    )

    private val poll = object : Runnable {
        override fun run() {
            worker.execute {
                try {
                    val l = link ?: connect().also { link = it }
                    for (r in rows) {
                        val got = l.getProp.invoke(l.prop, r.id, r.area)
                        val ok = got != null && (l.getStatus.invoke(got) as Int) == 0
                        val nv = if (ok) l.getValue.invoke(got) else null
                        runOnUiThread {
                            if (nv != null) vals[r.key] = nv else vals.remove(r.key)
                            render(r)
                        }
                    }
                } catch (e: Throwable) {
                    link = null
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
                    listOf(1, 2, 3, 4, 7).forEach { v ->
                        line.addView(button(dirNames[v] ?: v.toString()) { setFixed(r.id, r.area, v) })
                    }
                }
                Kind.SENSOR -> line.addView(label)
            }
            root.addView(line)
        }

        root.addView(TextView(this).apply { textSize = 17f; text = "Быстрые кнопки:" })
        val fixedLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (f in fixed) {
            fixedLine.addView(button(f.label) { setFixed(f.id, f.area, f.value) })
        }
        root.addView(fixedLine)

        root.addView(button("Поиск ID руля и ОЖ") { scan() })
        scanView = TextView(this).apply { textSize = 13f; text = "" }
        root.addView(scanView)

        setContentView(ScrollView(this).apply { addView(root) })
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
        val text = when (r.kind) {
            Kind.TEMP, Kind.SENSOR -> if (v == null) "—" else String.format(Locale.US, "%.1f °C", (v as Number).toDouble())
            Kind.INT -> v?.toString() ?: "—"
            Kind.LEVELS, Kind.DIR -> if (v == null) "—" else r.names[(v as Number).toInt()] ?: v.toString()
        }
        views[r.key]?.text = "${r.title}: $text"
    }

    /** Шаг по уровню, температуре или скорости. Одно нажатие — один шаг, в пределах диапазона. */
    private fun change(r: Row, sign: Int) {
        val cur = vals[r.key]
        val v: Any = when (r.kind) {
            Kind.LEVELS -> {
                val c = (cur as? Number)?.toInt() ?: 0
                val idx = r.levels.indexOf(c).let { if (it < 0) 0 else it }
                r.levels[(idx + sign).coerceIn(0, r.levels.size - 1)]
            }
            Kind.TEMP -> ((cur as? Number)?.toDouble() ?: 22.0)
                .let { (it + sign * r.step).coerceIn(r.min, r.max).toFloat() }
            Kind.INT -> ((cur as? Number)?.toDouble() ?: 4.0)
                .let { (it + sign * r.step).coerceIn(r.min, r.max).toInt() }
            Kind.DIR, Kind.SENSOR -> return
        }
        vals[r.key] = v
        render(r)
        write(r.id, r.area, v)
    }

    private fun setFixed(id: Int, area: Int, value: Any) {
        write(id, area, value)
    }

    private fun write(id: Int, area: Int, v: Any) {
        worker.execute {
            try {
                val l = link ?: connect().also { link = it }
                l.setProp.invoke(l.prop, l.cpvCtor.newInstance(id, area, v))
            } catch (e: Throwable) {
                link = null
            }
        }
    }

    /** Ищет в SDK поля с ключевыми словами руля и охлаждающей жидкости. Результат — на экране. */
    private fun scan() {
        scanView?.text = "Поиск..."
        worker.execute {
            val found = StringBuilder()
            try {
                val cl = dalvik.system.PathClassLoader(apk, ClassLoader.getSystemClassLoader())
                @Suppress("DEPRECATION")
                val df = dalvik.system.DexFile(apk)
                val names = java.util.Collections.list(df.entries()).filter { it.startsWith("mega.car.") }
                df.close()
                val key = Regex("STEER|WHEEL|HANDLE|COOLANT|ENG_TEMP|ENGINE_TEMP|WATER|ECT")
                for (n in names) {
                    val c = try { Class.forName(n, true, cl) } catch (e: Throwable) { continue }
                    for (f in c.declaredFields) {
                        if (!Modifier.isStatic(f.modifiers) || !key.containsMatchIn(f.name)) continue
                        val v = try { f.isAccessible = true; f.get(null).toString() } catch (e: Throwable) { "?" }
                        found.append(n).append('.').append(f.name).append(" = ").append(v).append('\n')
                    }
                }
            } catch (e: Throwable) {
                found.append("ошибка: ").append(e)
            }
            val text = if (found.isEmpty()) "ничего не найдено" else found.take(3000).toString()
            runOnUiThread { scanView?.text = text }
        }
    }

    /** ServiceManager → mega.car.CarService (ICar) → getCarService(PROPERTY_SERVICE) → ICarProperty. */
    private fun connect(): Link {
        val cl = dalvik.system.PathClassLoader(apk, ClassLoader.getSystemClassLoader())
        val mega = Class.forName("mega.car.MegaCar", true, cl)
        fun constStr(name: String): String =
            mega.getDeclaredField(name).also { it.isAccessible = true }.get(null).toString()
        val svc = constStr("CAR_SERVICE_BINDER_SERVICE_NAME")
        val propSvc = constStr("PROPERTY_SERVICE")
        val carBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, svc) as android.os.IBinder
        val iCar = Class.forName("mega.car.ICar\$Stub", true, cl)
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, carBinder)
        val propBinder = Class.forName("mega.car.ICar", true, cl)
            .getMethod("getCarService", String::class.java).invoke(iCar, propSvc) as android.os.IBinder
        val iProp = Class.forName("mega.car.hardware.property.ICarProperty", true, cl)
        val prop = Class.forName("mega.car.hardware.property.ICarProperty\$Stub", true, cl)
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, propBinder)!!
        val cpv = Class.forName("mega.car.hardware.CarPropertyValue", true, cl)
        return Link(
            prop = prop,
            getProp = iProp.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            setProp = iProp.getMethod("setProperty", cpv),
            cpvCtor = cpv.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java),
            getValue = cpv.getMethod("getValue"),
            getStatus = cpv.getMethod("getStatus")
        )
    }
}
