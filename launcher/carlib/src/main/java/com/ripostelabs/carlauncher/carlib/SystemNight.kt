package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.car.ICarService

/** RAV4-169: the system night mode the launcher asks the car service for, as its ICarService code. */
enum class SystemNight(val code: Int) {
    DAY(ICarService.NIGHT_MODE_DAY),
    NIGHT(ICarService.NIGHT_MODE_NIGHT),
}
