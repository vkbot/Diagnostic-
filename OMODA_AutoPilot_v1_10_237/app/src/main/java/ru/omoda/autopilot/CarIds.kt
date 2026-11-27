package ru.omoda.autopilot

/** Все ID свойств машины, которые использует приложение. Значения проверены тестами; см. заметки в памяти проекта. */
object CarIds {
    const val TEMP_LEFT = 33554551
    const val TEMP_RIGHT = 33554552
    const val FAN_SPEED = 33554448
    const val FAN_DIRECTION = 33554449
    const val SEAT_HEAT_DRV = 33554453
    const val SEAT_HEAT_PASS = 33554569
    const val SEAT_HEAT_RL = 33554570
    const val SEAT_HEAT_RR = 33554572
    const val SEAT_VENT_DRV = 33554454
    const val SEAT_VENT_PASS = 33554574
    const val SEAT_HEAT_FRONTLEFT = 33554568
    const val SEAT_HEAT_FRONTRIGHT = 33554569
    const val SENSOR_OUTSIDE = 33554496
    const val SENSOR_INSIDE = 33554497
    const val AC = 33554438
    const val SYNC = 33554436
    const val REAR_DEFROST = 33554459
    const val STEER_HEAT = 50331649
    const val FRONT_DEFROST = 33554486
    const val RECIRC = 33554451
    const val AUTO_AC = 33554435
    // Стандартные ID из файла штатного приложения (VehiclePropertyIds), по которым оно показывает сиденья
    const val HVAC_SEAT_TEMPERATURE = 356517131
    const val HVAC_SEAT_VENTILATION = 356517139
    const val HVAC_STEERING_WHEEL_HEAT = 289408269
}
