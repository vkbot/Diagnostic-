package ru.omoda.autopilot

import java.lang.reflect.Constructor
import java.io.File
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Общий доступ к свойствам машины через ICarProperty: одно подключение на процесс. */
object CarProps {
    private const val APK = "/system/priv-app/CarService/CarService.apk"

    private class Link(
        val prop: Any,
        val getProp: Method,
        val setProp: Method,
        val cpvCtor: Constructor<*>,
        val getValue: Method,
        val getStatus: Method
    )

    @Volatile private var link: Link? = null
    private val loader by lazy { dalvik.system.PathClassLoader(APK, ClassLoader.getSystemClassLoader()) }

    /** Значение или null, если свойство недоступно или статус не STATUS_AVAILABLE. */
    @Synchronized
    fun get(id: Int, area: Int): Any? = try {
        val l = link ?: connect().also { link = it }
        val got = l.getProp.invoke(l.prop, id, area)
        if (got != null && (l.getStatus.invoke(got) as Int) == 0) l.getValue.invoke(got) else null
    } catch (e: Throwable) {
        link = null
        null
    }

    /** Код статуса чтения свойства (0 — доступно). Для диагностики строк, которые не читаются. */
    @Synchronized
    fun status(id: Int, area: Int): Int? = try {
        val l = link ?: connect().also { link = it }
        val got = l.getProp.invoke(l.prop, id, area)
        if (got == null) -1 else l.getStatus.invoke(got) as Int
    } catch (e: Throwable) {
        link = null
        null
    }

    /** Null — записано, иначе причина отказа. */
    @Synchronized
    fun set(id: Int, area: Int, v: Any): String? = try {
        val l = link ?: connect().also { link = it }
        l.setProp.invoke(l.prop, l.cpvCtor.newInstance(id, area, v))
        null
    } catch (e: Throwable) {
        link = null
        (e.cause ?: e).javaClass.simpleName
    }

    /** Зоны в порядке штатной кнопки (по монитору): 1024, 256, 80, 64, 16, 0. */
    private val ZONES = intArrayOf(0x400, 0x100, 0x50, 0x40, 0x10, 0x0)
    private const val WAIT_MS = 3000L
    private const val POLL_MS = 200L

    /**
     * Пишет значение во все зоны по очереди. После записи опрашивает значение до 3 с и пишет в лог,
     * сменилось ли оно и за сколько миллисекунд. Если не сменилось — машина не приняла запись.
     */
    @Synchronized fun writeZones(id: Int, value: Any, log: (String) -> Unit) {
        for (zone in ZONES) {
            val before = get(id, zone)
            val err = set(id, zone, value)
            if (before?.toString() == value.toString()) {
                log("id=$id зона=0x${Integer.toHexString(zone)} было=$before записано=$value " +
                    (if (err == null) "ок" else "ОШИБКА $err") + " уже было такое значение, смену не проверяю")
                continue
            }
            val t0 = System.currentTimeMillis()
            var after: Any? = null
            var ok = false
            while (System.currentTimeMillis() - t0 < WAIT_MS) {
                Thread.sleep(POLL_MS)
                after = get(id, zone)
                if (after != null && after.toString() != before?.toString()) {
                    ok = true
                    break
                }
            }
            val took = System.currentTimeMillis() - t0
            log("id=$id зона=0x${Integer.toHexString(zone)} было=$before записано=$value " +
                (if (err == null) "ок" else "ОШИБКА $err") + " прочитано=$after " +
                (if (ok) "сменилось за $took мс" else "НЕ сменилось за ${WAIT_MS} мс"))
        }
    }

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

/** Лог в файл: /sdcard/Download/omoda_climate_log.txt, каждая строка со временем. */
object ClimateLog {
    private val file = File("/sdcard/Download/omoda_climate_log.txt")

    @Synchronized
    fun line(msg: String) {
        try {
            file.appendText(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + msg + "\n")
        } catch (e: Throwable) {
            // лог не должен ломать запись
        }
    }
}
