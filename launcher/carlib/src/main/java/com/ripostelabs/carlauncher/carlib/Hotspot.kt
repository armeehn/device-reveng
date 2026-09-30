package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.car.ICarService

/** RAV4-216: the Wi-Fi hotspot the launcher asks the car service for, as its ICarService code. */
enum class Hotspot(val code: Int) {
    OFF(ICarService.HOTSPOT_OFF),
    ON(ICarService.HOTSPOT_ON),
    ;

    companion object {
        fun of(code: Int): Hotspot? = entries.firstOrNull { it.code == code }
    }
}
