package com.ripostelabs.carlauncher.input

import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.WheelFunction
import com.ripostelabs.carlauncher.carlib.WheelKeyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `STEER_WHEEL_INFOR` LPARAM is a learned slot + 1. With a map, only learned functions
 * fire; without one the legacy CAR_KEY reading stays; the panel fallbacks are always CAR_KEY.
 */
class SwcResolveTest {

    private val learned = WheelKeyMap.parse(
        """{"svg_wheel_next_c":"0","svg_wheel_pre_c":"1","svg_wheel_s_add":"2","svg_wheel_mode_home":"4"}""",
    )

    private fun slotKey(lparam: Int) = CarEvents.SwcKey(lparam, down = true, voltage = 0)
    private fun panelKey(carKey: Int) =
        CarEvents.SwcKey(carKey, down = true, voltage = 0, space = CarEvents.KeySpace.CAR_KEY)

    @Test
    fun learnedSlotResolvesThroughTheMap() {
        assertEquals(NavKey.MEDIA_NEXT, SwcNavigator.resolve(slotKey(1), learned)) // slot 0
        assertEquals(NavKey.MEDIA_PREV, SwcNavigator.resolve(slotKey(2), learned)) // slot 1
        assertEquals(NavKey.HOME, SwcNavigator.resolve(slotKey(5), learned)) // slot 4
    }

    @Test
    fun learnedButNotOursIsSilent() {
        assertNull(SwcNavigator.resolve(slotKey(3), learned)) // volume up: the gateway's
    }

    @Test
    fun unlearnedSlotIsSilentWhenAMapExists() {
        // LPARAM 4 = slot 3, unlearned. As a CAR_KEY it would have been PREV — must not fire.
        assertNull(SwcNavigator.resolve(slotKey(4), learned))
    }

    @Test
    fun noMapFallsBackToCarKeyReading() {
        assertEquals(NavKey.MEDIA_NEXT, SwcNavigator.resolve(slotKey(CarEvents.CAR_KEY_NEXT), WheelKeyMap.EMPTY))
        assertEquals(NavKey.HOME, SwcNavigator.resolve(slotKey(CarEvents.CAR_KEY_HOME), WheelKeyMap.EMPTY))
    }

    @Test
    fun panelFallbackIgnoresTheMap() {
        // CAR_KEY_PREV = 4 would be slot 3 (unlearned) if misread as a slot.
        assertEquals(NavKey.MEDIA_PREV, SwcNavigator.resolve(panelKey(CarEvents.CAR_KEY_PREV), learned))
    }

    @Test
    fun wheelFunctionTable() {
        assertEquals(NavKey.OPEN_MEDIA, SwcNavigator.fromWheel(WheelFunction.MODE))
        assertEquals(NavKey.OPEN_MEDIA, SwcNavigator.fromWheel(WheelFunction.MUSIC))
        assertEquals(NavKey.OPEN_RADIO, SwcNavigator.fromWheel(WheelFunction.FM))
        assertEquals(NavKey.BACK, SwcNavigator.fromWheel(WheelFunction.BACK))
        assertEquals(NavKey.CENTER, SwcNavigator.fromWheel(WheelFunction.OK))
        assertNull(SwcNavigator.fromWheel(WheelFunction.HANG_UP))
        assertNull(SwcNavigator.fromWheel(WheelFunction.VOICE))
    }
}
