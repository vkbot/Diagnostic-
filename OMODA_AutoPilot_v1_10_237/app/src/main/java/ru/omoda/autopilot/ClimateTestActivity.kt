package ru.omoda.autopilot

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors

/** Тестовый климат: непроверенные ID, монитор изменений и поиск ID. Ничего отсюда не переносится в обычный экран без проверки. */
class ClimateTestActivity : Activity() {
    private val apk = "/system/priv-app/CarService/CarService.apk"
    private val loader by lazy { dalvik.system.PathClassLoader(apk, ClassLoader.getSystemClassLoader()) }
    private val worker = Executors.newCachedThreadPool()
    private var link: Link? = null
    private var scanView: TextView? = null
    @Volatile private var monitoring = false
    private val monLog = ArrayDeque<String>()
    private val screenLog = ArrayDeque<String>()

    private class Link(
        val prop: Any,
        val getProp: Method,
        val setProp: Method,
        val cpvCtor: Constructor<*>,
        val getValue: Method,
        val getStatus: Method
    )

    private class Fixed(val label: String, val id: Int, val area: Int, val value: Any)

    private val tests = listOf(
        Fixed("Авто обдув 1 (463)", 33554463, 80, 1),
        Fixed("AUTO ВЫКЛ (435=0, зона 80)", CarIds.AUTO_AC, 80, 0),
        Fixed("AUTO фронт ВЫКЛ (540=0)", 33554540, 0, 0),
        Fixed("MAX A/C 1 (439)", 33554439, 0, 1),
        Fixed("MAX A/C 0", 33554439, 0, 0),
        Fixed("SWT? 500 =1", 33554500, 0, 1),
        Fixed("SWT? 500 =0", 33554500, 0, 0),
        Fixed("SWT_AUTO? 503 =1", 33554503, 0, 1),
        Fixed("SWT_AUTO? 503 =0", 33554503, 0, 0),
        Fixed("вент пасс 2 (574/64)", 33554574, 64, 2),
        Fixed("вент пасс ВЫКЛ (574=1)", 33554574, 64, 1),
        Fixed("подогрев вод ВЫКЛ (453=1)", CarIds.SEAT_HEAT_DRV, 16, 1)
    )

    @Volatile private var writeEnabled = false
    private lateinit var writeBtn: Button

    /** Все записи этого экрана идут через проверку переключателя «ЗАПИСЬ». */
    private fun guardedWrite(id: Int, value: Any, log: (String) -> Unit) {
        if (!writeEnabled) {
            log("запись выключена: нажмите «ЗАПИСЬ: ВЫКЛ», чтобы разрешить")
            return
        }
        CarProps.writeZones(id, value, log)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply { text = "Состояние климата"; textSize = 22f })
        // Текущее состояние: обновляется каждые 2 секунды, только чтение
        stateView = TextView(this).apply { textSize = 16f; text = "читаю..." }
        root.addView(stateView)
        root.addView(button("БЫСТРЫЙ ПРОГРЕВ: ВКЛ (записать 1)") { fastHeat(1) })
        root.addView(button("БЫСТРЫЙ ПРОГРЕВ: ВЫКЛ (записать 0)") { fastHeat(0) })
        root.addView(button("МОНИТОР (жмите кнопку на штатной панели)") { toggleMonitor() })
        root.addView(button("ШТАТНЫЙ КЛИМАТ: ИСКАТЬ ID СИДЕНИЙ") { findStockIds() })
        root.addView(button("ОСТАНОВИТЬ МОНИТОР") {
            monitoring = false
            setScan("Монитор остановлен")
        })
        scanView = TextView(this).apply { textSize = 15f; text = "" }
        root.addView(scanView)
        setContentView(ScrollView(this).apply { addView(root) })
        stateHandler.post(statePoll)
    }

    private lateinit var stateView: TextView
    private val stateHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Что сейчас читает машина по основным устройствам. «нет чтения» — машина не отдаёт значение. */
    private val stateItems = listOf(
        Triple("Подогрев водителя", CarIds.SEAT_HEAT_DRV, 16),
        Triple("Подогрев пассажира", CarIds.SEAT_HEAT_PASS, 64),
        Triple("Подогрев задний левый", CarIds.SEAT_HEAT_RL, 256),
        Triple("Подогрев задний правый", CarIds.SEAT_HEAT_RR, 1024),
        Triple("Вентиляция водителя", CarIds.SEAT_VENT_DRV, 16),
        Triple("Вентиляция пассажира", CarIds.SEAT_VENT_PASS, 64),
        Triple("Обогрев руля", CarIds.STEER_HEAT, 1024),
        Triple("Заднее стекло", CarIds.REAR_DEFROST, 1024),
        Triple("Кондиционер A/C", CarIds.AC, 1024),
        Triple("SYNC", CarIds.SYNC, 1024),
        Triple("Лобовой запрос обдува", CarIds.FRONT_DEFROST, 0),
        Triple("Стандартный HVAC: водитель (зона 1)", CarIds.HVAC_SEAT_TEMPERATURE, 1),
        Triple("Стандартный HVAC: пассажир (зона 4)", CarIds.HVAC_SEAT_TEMPERATURE, 4),
        Triple("Быстрый прогрев ID_AC_FAST_HEAT (зона 0)", 33554521, 0),
        Triple("Быстрый прогрев ID_AC_FAST_HEAT (зона 1024)", 33554521, 1024),
        Triple("Вентиляция водителя ID_SEAT_VENT_FRONTLEFT", 33554573, 16)
    )

    private val statePoll = object : Runnable {
        override fun run() {
            Thread {
                val text = stateItems.joinToString("\n") { (name, id, area) ->
                    val v = CarProps.get(id, area)
                    "$name: ${v?.toString() ?: "нет чтения"}"
                }
                runOnUiThread { stateView.text = text }
            }.start()
            stateHandler.postDelayed(this, 2000L)
        }
    }

    /** Ищет в коде штатного климат-приложения константы ID сидений: так узнаём, какие свойства оно читает. Только чтение. */
    private fun findStockIds() {
        setScan("ищу штатное климат-приложение...")
        worker.execute {
            try {
                // Климат — часть системы автомобиля: ищем в системных пакетах производителя (mega, chery, systemui, car)
                val apps = packageManager.getInstalledApplications(0).filter { ai ->
                    val n = ai.packageName.lowercase()
                    n.contains("mega") || n.contains("chery") || n.contains("systemui") || n.contains("car") || n.contains("omoda")
                }.filter { it.packageName != packageName }
                if (apps.isEmpty()) {
                    setScan("системные пакеты производителя не найдены")
                    return@execute
                }
                val out = StringBuilder()
                val re = Regex("SEAT|HEAT|VENT|FRONT|DRIVER|PASS|DEFROST", RegexOption.IGNORE_CASE)
                var found = 0
                for (ai in apps) {
                    out.append("== ${ai.packageName}\n")
                    val cl = dalvik.system.PathClassLoader(ai.sourceDir, ClassLoader.getSystemClassLoader())
                    @Suppress("DEPRECATION")
                    val df = dalvik.system.DexFile(ai.sourceDir)
                    val names = java.util.Collections.list(df.entries())
                    df.close()
                    for (cn in names) {
                        try {
                            val c = Class.forName(cn, false, cl)
                            for (f in c.declaredFields) {
                                if (Modifier.isStatic(f.modifiers) && f.type == Int::class.javaPrimitiveType && re.containsMatchIn(f.name)) {
                                    f.isAccessible = true
                                    out.append("${c.simpleName}.${f.name} = ${f.get(null)}\n")
                                    found++
                                }
                            }
                        } catch (e: Throwable) {
                            // класс не загрузился: пропускаем
                        }
                    }
                }
                // Полный список — в файл: на экране слишком много строк
                val file = java.io.File("/sdcard/Download/omoda_seat_ids.txt")
                file.writeText(out.toString())
                setScan("поиск готов: найдено констант $found, полный список в файле ${file.path}")
            } catch (e: Throwable) {
                setScan("ошибка поиска: $e")
            }
        }
    }

    /** Пишет значение в ID_AC_FAST_HEAT (зона 0), сразу читает обратно и показывает ошибку или ответ машины. */
    private fun fastHeat(v: Int) {
        worker.execute {
            val err = CarProps.set(33554521, 0, v)
            Thread.sleep(700)
            val back = CarProps.get(33554521, 0)
            setScan("быстрый прогрев: записано $v, ошибка ${err ?: "нет"}, машина показывает ${back ?: "нет чтения"}")
        }
    }

    override fun onPause() {
        // Уходя с экрана, останавливаем монитор: он не должен работать в фоне
        monitoring = false
        super.onPause()
    }

    override fun onDestroy() {
        stateHandler.removeCallbacksAndMessages(null)
        monitoring = false
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            setOnClickListener { onClick() }
        }

    private fun setScan(msg: String) {
        runOnUiThread { scanView?.text = msg }
    }

    /**
     * Запись без автовозврата: читаем исходное значение и статус, запоминаем исходное, пишем новое,
     * читаем обратно. Вернуть можно кнопкой «Вернуть исходное». Прочитанное значение — не доказательство реакции машины.
     */
    private fun write(id: Int, area: Int, v: Any) {
        if (!writeEnabled) {
            setScan("запись выключена: нажмите «ЗАПИСЬ: ВЫКЛ», чтобы разрешить")
            return
        }
        worker.execute {
            try {
                val l = link ?: connect().also { link = it }
                val before = l.getProp.invoke(l.prop, id, area)
                if (before == null || (l.getStatus.invoke(before) as Int) != 0) {
                    setScan("Запись отменена: свойство недоступно (id=$id, зона=$area)")
                    return@execute
                }
                val old = l.getValue.invoke(before)
                if (old != null) {
                    synchronized(savedOld) { savedOld.putIfAbsent("$id/$area", Triple(id, area, old)) }
                }
                l.setProp.invoke(l.prop, l.cpvCtor.newInstance(id, area, v))
                Thread.sleep(1500)
                val seen = l.getProp.invoke(l.prop, id, area)?.let { l.getValue.invoke(it) }
                setScan("записано $v (id=$id, зона=$area), прочитано обратно: $seen, исходное: $old")
            } catch (e: Throwable) {
                link = null
                setScan("ошибка записи: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}: ${e.cause?.message ?: e.message}")
            }
        }
    }

    /** Выключение во всех зонах, где штатная кнопка меняет значение (по монитору: 1024, 256, 64, 16, 0). */
    /** Проверка значений выключения: ВКЛ, затем значения 2 и 3 по очереди, затем возврат к ВЫКЛ. Всё в лог. */
    private fun runOffTest() {
        val items = listOf(
            Triple("руль", CarIds.STEER_HEAT, Pair(1, 3)),
            Triple("заднее стекло", CarIds.REAR_DEFROST, Pair(1, 3)),
            Triple("кондиционер A/C", CarIds.AC, Pair(1, 2)),
            Triple("SYNC", CarIds.SYNC, Pair(0, 1))
        )
        worker.execute {
            show("=== ПРОВЕРКА значений выключения начата ===")
            for ((name, id, onOff) in items) {
                show("== $name: ВКЛ (${onOff.first})")
                guardedWrite(id, onOff.first) { show(it) }
                for (v in listOf(2, 3)) {
                    show("== $name: пробую значение $v")
                    guardedWrite(id, v) { show(it) }
                }
                show("== $name: возврат ВЫКЛ (${onOff.second})")
                guardedWrite(id, onOff.second) { show(it) }
            }
            show("=== ПРОВЕРКА готова: см. omoda_climate_log.txt ===")
        }
    }

    /** Единый тест: для каждого устройства ВКЛ, затем ВЫКЛ, по всем зонам, всё в лог и на экран. */
    private fun runTest() {
        val items = listOf(
            Triple("руль (50331649)", CarIds.STEER_HEAT, Pair(1, 3)),
            Triple("заднее стекло (33554459)", CarIds.REAR_DEFROST, Pair(1, 3)),
            Triple("кондиционер A/C (33554438)", CarIds.AC, Pair(1, 2)),
            Triple("SYNC (33554436)", CarIds.SYNC, Pair(0, 1))
        )
        worker.execute {
            show("=== ТЕСТ начат ===")
            for ((name, id, onOff) in items) {
                show("== $name: ВКЛ (${onOff.first})")
                guardedWrite(id, onOff.first) { show(it) }
                show("== $name: ВЫКЛ (${onOff.second})")
                guardedWrite(id, onOff.second) { show(it) }
            }
            show("=== ТЕСТ готов: см. omoda_climate_log.txt ===")
        }
    }

    /** Запись во все зоны по очереди с логом (файл и последние строки на экране). */
    private fun allZones(id: Int, value: Int) {
        worker.execute {
            guardedWrite(id, value) { show(it) }
            show("готово: id=$id значение=$value")
        }
    }

    /** Только чтение: снимок всех int-свойств в диапазонах, где могут быть обогревы. Ничего не пишет. */
    private val winSnaps = arrayOfNulls<Map<String, String>>(2)

    /** Все ID_ из классов mega.* в CarService, с именами (как у монитора). */
    private var idCache: List<Pair<String, Int>>? = null

    /** Список ID читаем один раз за сеанс экрана. */
    private fun allIds(): List<Pair<String, Int>> = idCache ?: computeIds().also { idCache = it }

    private fun computeIds(): List<Pair<String, Int>> {
        val cl = dalvik.system.PathClassLoader(apk, ClassLoader.getSystemClassLoader())
        @Suppress("DEPRECATION")
        val df = dalvik.system.DexFile(apk)
        val classNames = java.util.Collections.list(df.entries()).filter { it.startsWith("mega.") }
        df.close()
        return classNames.filter { !it.contains("fuel", true) && !it.contains("network", true) }.flatMap { cn ->
            try {
                Class.forName(cn, false, cl).declaredFields
                    .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith("ID_") }
                    .map { f -> f.isAccessible = true; "${cn.removePrefix("mega.car.config.")}.${f.name}" to (f.get(null) as Int) }
            } catch (e: Throwable) {
                emptyList()
            }
        }.distinctBy { it.second }
    }

    private fun readRange(l: Link, onProgress: (String) -> Unit): Map<String, String> {
        val out = HashMap<String, String>()
        val ids = try { allIds() } catch (e: Throwable) { emptyList() }
        // Зоны: 0 и все битовые маски, которые есть у машины
        val areas = intArrayOf(0, 1, 2, 4, 8, 16, 32, 64, 80, 128, 256, 512, 1024)
        var count = 0
        for ((name, id) in ids) {
            count++
            if (count % 64 == 0) onProgress("читаю: $count ID из ${ids.size}...")
            for (a in areas) {
            try {
                val got = l.getProp.invoke(l.prop, id, a) ?: continue
                if ((l.getStatus.invoke(got) as Int) != 0) continue
                out["$name $id/$a"] = l.getValue.invoke(got).toString()
            } catch (e: Throwable) {
                // свойство недоступно: пропускаем
            }
            }
        }
        return out
    }

    private fun snapWindow(n: Int) {
        setScan("Снимок $n: читаю диапазоны, подождите...")
        worker.execute {
            try {
                val l = link ?: connect().also { link = it }
                val m = readRange(l) { msg -> setScan("Снимок $n: $msg") }
                winSnaps[n - 1] = m
                show("ЛОБОВОЕ снимок $n готов: ${m.size} значений")
            } catch (e: Throwable) {
                link = null
                setScan("ошибка снимка: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun compareWin() {
        val a = winSnaps[0]
        val b = winSnaps[1]
        if (a == null || b == null) { setScan("Сначала сделайте оба снимка"); return }
        val diffs = a.keys.intersect(b.keys).filter { a[it] != b[it] }.sorted()
        if (diffs.isEmpty()) show("ЛОБОВОЕ сравнение: различий нет")
        else {
            // Полный список в лог, на экране — количество и первые строки
            diffs.forEach { ClimateLog.line("ЛОБОВОЕ diff: $it: ${a[it]} → ${b[it]}") }
            show("ЛОБОВОЕ сравнение: различий ${diffs.size}, полный список в omoda_climate_log.txt")
        }
    }

    /** Только чтение: метаданные свойств из статической карты CarPropertyProvider. Ничего не пишет. Не проверено на ГУ. */
    private fun dumpRegistry() {
        setScan("Читаю реестр свойств...")
        worker.execute {
            var count = 0
            try {
                val cl = dalvik.system.PathClassLoader(apk, ClassLoader.getSystemClassLoader())
                val prov = Class.forName("mega.car.config.CarPropertyProvider", true, cl)
                val f = prov.getDeclaredField("CAR_PROPERTY_ANNOTATION_DEFINE").apply { isAccessible = true }
                val sa = f.get(null)
                val cls = sa.javaClass
                val size = cls.getMethod("size").invoke(sa) as Int
                val keyAt = cls.getMethod("keyAt", Int::class.javaPrimitiveType)
                val valueAt = cls.getMethod("valueAt", Int::class.javaPrimitiveType)
                // Имён в аннотации нет, поэтому пишем ВСЕ свойства в лог; на экране считаем по классам климата
                val filter = Regex("Climate|Hvac|Seat|Air|Glass|Window|Defrost|Defog|Wiper|Mirror|Heat|Steer", RegexOption.IGNORE_CASE)
                ClimateLog.line("=== РЕЕСТР: всего свойств $size ===")
                for (i in 0 until size) {
                    val id = keyAt.invoke(sa, i) as Int
                    val ann = valueAt.invoke(sa, i)?.toString() ?: continue
                    ClimateLog.line("${id} 0x${Integer.toHexString(id)} $ann")
                    if (filter.containsMatchIn(ann)) count++
                }
                ClimateLog.line("=== РЕЕСТР готов: всего $size, по климатическому фильтру $count ===")
                show("РЕЕСТР: всего $size, по климату $count. Полный список в omoda_climate_log.txt")
            } catch (e: Throwable) {
                show("РЕЕСТР ошибка: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun show(msg: String) {
        ClimateLog.line(msg)
        val text = synchronized(screenLog) {
            screenLog.addLast(msg)
            while (screenLog.size > 25) screenLog.removeFirst()
            screenLog.joinToString("\n")
        }
        setScan(text)
    }

    /** Возвращает исходные значения всех тестовых записей, которые были сделаны в этом сеансе. */
    private fun restoreAll() {
        worker.execute {
            val items = synchronized(savedOld) { savedOld.values.toList().also { savedOld.clear() } }
            if (items.isEmpty()) {
                setScan("Нечего возвращать")
                return@execute
            }
            val l = try {
                link ?: connect().also { link = it }
            } catch (e: Throwable) {
                setScan("нет связи с машиной: $e")
                return@execute
            }
            val report = StringBuilder()
            for ((id, area, old) in items) {
                try {
                    l.setProp.invoke(l.prop, l.cpvCtor.newInstance(id, area, old))
                    report.append("id=$id зона=$area -> $old: ок\n")
                } catch (e: Throwable) {
                    link = null
                    report.append("id=$id зона=$area: ОШИБКА, верните вручную (было $old)\n")
                }
            }
            setScan(report.toString())
        }
    }

    /** Показывает, какие ID климата меняются при нажатии на штатной панели. */
    private fun toggleMonitor() {
        if (monitoring) {
            monitoring = false
            setScan("Монитор остановлен")
            return
        }
        monitoring = true
        synchronized(monLog) { monLog.clear() }
        setScan("Монитор запущен: нажмите кнопку на штатной панели и ждите строку")
        worker.execute {
            val names = try {
                val cl = dalvik.system.PathClassLoader(apk, ClassLoader.getSystemClassLoader())
                @Suppress("DEPRECATION")
                val df = dalvik.system.DexFile(apk)
                val classNames = java.util.Collections.list(df.entries()).filter { it.startsWith("mega.") }
                df.close()
                classNames.filter { !it.contains("fuel", true) && !it.contains("network", true) }.flatMap { cn ->
                    try {
                        Class.forName(cn, false, cl).declaredFields
                            .filter {
                                Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType &&
                                    it.name.startsWith("ID_") && !it.name.contains("FUEL", true) && !it.name.contains("NETWORK", true) && !it.name.contains("EP_MOTOR_SPEED", true)
                            }
                            .map { f -> f.isAccessible = true; "${cn.removePrefix("mega.car.config.")}.${f.name}" to (f.get(null) as Int) }
                    } catch (e: Throwable) {
                        emptyList()
                    }
                }
            } catch (e: Throwable) {
                monitoring = false
                setScan("ошибка списка ID: $e")
                return@execute
            }
            val l = try {
                link ?: connect().also { link = it }
            } catch (e: Throwable) {
                monitoring = false
                setScan("нет связи с машиной: $e")
                return@execute
            }
            // Все зоны, которые встречались в тестах: у передних сидений состояние может лежать в других зонах
            val areas = intArrayOf(0, 1, 2, 4, 8, 16, 32, 64, 80, 128, 256, 512, 1024)
            val last = HashMap<String, String>()
            var pass = 0
            while (monitoring) {
                pass++
                if (pass == 2) setScan("Базовый проход готов: можно нажимать штатную кнопку")
                for ((name, id) in names) {
                    for (a in areas) {
                        val cur = try {
                            val got = l.getProp.invoke(l.prop, id, a)
                            if (got == null) "null" else {
                                val st = l.getStatus.invoke(got) as Int
                                if (st != 0) "статус$st" else l.getValue.invoke(got).toString()
                            }
                        } catch (e: Throwable) {
                            "ошибка"
                        }
                        val prev = last["$id/$a"]
                        if (prev != null && prev != cur && cur != "ошибка") {
                            val text = synchronized(monLog) {
                                monLog.addFirst("$name зона=0x${Integer.toHexString(a)}: $prev → $cur")
                                while (monLog.size > 40) monLog.removeLast()
                                monLog.joinToString("\n")
                            }
                            setScan("МОНИТОР (жмите кнопку на штатной панели):\n$text")
                        }
                        last["$id/$a"] = cur
                    }
                }
                // Пауза между проходами: без неё монитор без остановки грузит службу машины и мешает записям
                Thread.sleep(1500)
            }
        }
    }

    /** Исходные значения до тестовых записей: для кнопки «вернуть исходное». Автовозврата нет. */
    private val savedOld = LinkedHashMap<String, Triple<Int, Int, Any>>()
    private var snap1: Map<String, Double>? = null
    private var snap2: Map<String, Double>? = null

    /** Все ID с TEMP в названии из классов mega.car.*, у которых есть численное значение. */
    private fun collectTemps(): Map<String, Double> {
        @Suppress("DEPRECATION")
        val df = dalvik.system.DexFile(apk)
        val classes = java.util.Collections.list(df.entries()).filter { it.startsWith("mega.car.") }
        df.close()
        val out = LinkedHashMap<String, Double>()
        val areas = intArrayOf(0, 16, 64, 80, 256, 1024)
        for (cn in classes) {
            val fields = try { Class.forName(cn, false, loader).declaredFields } catch (e: Throwable) { continue }
            for (f in fields) {
                if (!Modifier.isStatic(f.modifiers) || f.type != Int::class.javaPrimitiveType) continue
                if (!f.name.startsWith("ID_") || !f.name.contains("TEMP")) continue
                f.isAccessible = true
                val id = f.get(null) as Int
                for (a in areas) {
                    val v = CarProps.get(id, a) as? Number ?: continue
                    out["${cn.removePrefix("mega.car.")}.${f.name}@0x${Integer.toHexString(a)}"] = v.toDouble()
                }
            }
        }
        return out
    }

    private fun snapshot(n: Int) {
        setScan("Снимаю температуры...")
        worker.execute {
            val m = collectTemps()
            if (n == 1) snap1 = m else snap2 = m
            setScan("Снимок $n: ${m.size} значений. " + if (n == 1) "Прогрейте двигатель и сделайте снимок ТЁПЛЫЙ" else "Теперь нажмите «сравнить»")
        }
    }

    private fun compareSnaps() {
        val a = snap1
        val b = snap2
        if (a == null || b == null) {
            setScan("Нужны оба снимка: ХОЛОДНЫЙ и ТЁПЛЫЙ")
            return
        }
        val diffs = a.keys.intersect(b.keys).mapNotNull { k ->
            val d = b[k]!! - a[k]!!
            if (kotlin.math.abs(d) < 0.01) null else Pair(k, d)
        }.sortedByDescending { kotlin.math.abs(it.second) }.take(60)
        setScan(
            if (diffs.isEmpty()) "Изменений нет"
            else "Изменилось (по величине):\n" + diffs.joinToString("\n") { (k, d) ->
                "$k: ${"%.1f".format(a[k])} → ${"%.1f".format(b[k])} (Δ ${"%+.1f".format(d)})"
            }
        )
    }

    private fun showAllTemps() {
        setScan("Считаю...")
        worker.execute {
            val m = collectTemps()
            setScan(if (m.isEmpty()) "Ни одного значения" else m.entries.joinToString("\n") { "${it.key} = ${"%.1f".format(it.value)}" }.take(8000))
        }
    }

    /** Ищет в SDK поля с ключевыми словами руля и охлаждающей жидкости, плюс все enum-ы Climate. */
    private fun scan() {
        setScan("Поиск...")
        worker.execute {
            val found = StringBuilder()
            try {
                val cl = dalvik.system.PathClassLoader(apk, ClassLoader.getSystemClassLoader())
                @Suppress("DEPRECATION")
                val df = dalvik.system.DexFile(apk)
                val names = java.util.Collections.list(df.entries()).filter { it.startsWith("mega.car.") }
                df.close()
                val key = Regex("STEER|STRW|STRG|WHEEL|HANDLE|SWT|HEAT|COOLANT|ENG_TEMP|ENGINE_TEMP|WATER|ECT")
                for (n in names) {
                    val c = try { Class.forName(n, false, cl) } catch (e: Throwable) { continue }
                    val allFields = n.startsWith("mega.car.config.Climate\$")
                    for (f in c.declaredFields) {
                        if (!Modifier.isStatic(f.modifiers) || !(allFields || key.containsMatchIn(f.name))) continue
                        val v = try { f.isAccessible = true; f.get(null).toString() } catch (e: Throwable) { "?" }
                        found.append(n).append('.').append(f.name).append(" = ").append(v).append('\n')
                    }
                }
            } catch (e: Throwable) {
                found.append("ошибка: ").append(e)
            }
            setScan(if (found.isEmpty()) "ничего не найдено" else found.take(8000).toString())
        }
    }

    /** ServiceManager → mega.car.CarService (ICar) → getCarService(PROPERTY_SERVICE) → ICarProperty. */
    private fun connect(): Link {
        val cl = loader
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
