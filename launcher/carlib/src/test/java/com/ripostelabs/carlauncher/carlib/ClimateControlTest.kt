package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The CAN app matches the action with `startsWith("CAR_AIR_KEY_KEY")` and reads
 * `car_key_value` (`CB/CarAirClickWithVoice.java:460-462`); the values are
 * `CanUtils.CAR_AIR_KEY_*` (`CB/CanUtils.java:52-180`). Pin all three so a rename here cannot
 * silently turn every button into the receiver's -1 default.
 */
class ClimateControlTest {

    @Test
    fun actionAndExtraMatchReceiver() {
        val key = ClimateControl.keyBroadcast(ClimateButton.AC)

        assertEquals("CAR_AIR_KEY_KEY", key.action)
        assertEquals("car_key_value", key.extraName)
    }

    @Test
    fun keyValuesMatchCanUtils() {
        val vendor = mapOf(
            ClimateButton.POWER to 0,
            ClimateButton.FAN_UP to 1,
            ClimateButton.FAN_DOWN to 2,
            ClimateButton.LEFT_TEMP_UP to 3,
            ClimateButton.LEFT_TEMP_DOWN to 4,
            ClimateButton.RIGHT_TEMP_UP to 5,
            ClimateButton.RIGHT_TEMP_DOWN to 6,
            ClimateButton.AUTO to 7,
            ClimateButton.AC to 8,
            ClimateButton.AC_MAX to 9,
            ClimateButton.DUAL to 10,
            ClimateButton.RECIRCULATE to 12,
            ClimateButton.FRONT_DEFROST to 15,
            ClimateButton.REAR_DEFROST to 16,
            ClimateButton.LEFT_SEAT_COOL to 17,
            ClimateButton.LEFT_SEAT_HEAT to 18,
            ClimateButton.RIGHT_SEAT_COOL to 19,
            ClimateButton.RIGHT_SEAT_HEAT to 20,
            ClimateButton.MODE to 21,
            ClimateButton.ECO to 40,
            ClimateButton.REAR_LOCK to 49,
            ClimateButton.SYNC to 66,
        )

        // Every button is covered, and each carries exactly the vendor constant.
        assertEquals(ClimateButton.entries.toSet(), vendor.keys)
        ClimateButton.entries.forEach { button ->
            assertEquals(button.name, vendor[button], ClimateControl.keyBroadcast(button).keyValue)
        }
    }

    @Test
    fun keyValuesAreUnique() {
        val values = ClimateButton.entries.map { it.keyValue }

        assertEquals(values.size, values.toSet().size)
    }
}
