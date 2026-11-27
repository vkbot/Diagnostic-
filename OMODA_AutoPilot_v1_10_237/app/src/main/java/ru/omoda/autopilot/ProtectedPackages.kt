package ru.omoda.autopilot

/** Единый список пакетов, которые нельзя скрывать и останавливать. */
object ProtectedPackages {
    /** Системные пакеты Android, без которых ГУ не работает. */
    val SYSTEM = setOf("android", "com.android.systemui", "com.android.launcher3", "com.android.settings")

    /** Автомобильные сервисы: имена из журналов ГУ. Перед расширением сверить по pm list packages на ГУ. */
    val CAR = setOf("com.android.car", "com.mega.car")

    fun isProtected(pkg: String, self: String): Boolean =
        pkg == self || pkg in SYSTEM || pkg in CAR
}
