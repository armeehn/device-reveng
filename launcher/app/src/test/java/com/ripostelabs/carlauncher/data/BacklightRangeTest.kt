package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.CarService
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Set_Day_Light` / `Set_Night_Light` are 0..20 (vendor slider `DataManage.java:256`), and
 * `sendBacklight` takes them as bytes. The old wrapper clamped to 0..255 and sent (level, 0),
 * which would have zeroed the night target.
 */
class BacklightRangeTest {

    @Test
    fun clampStaysInsideTheVendorBand() {
        assertEquals(0, CarService.clampBacklight(-5))
        assertEquals(0, CarService.clampBacklight(0))
        assertEquals(20, CarService.clampBacklight(20))
        assertEquals(20, CarService.clampBacklight(255))
    }

    @Test
    fun percentMapsLinearlyOntoTheBand() {
        assertEquals(0, BrightnessController.percentToBacklight(0))
        assertEquals(10, BrightnessController.percentToBacklight(50))
        assertEquals(20, BrightnessController.percentToBacklight(100))
        assertEquals(20, BrightnessController.percentToBacklight(140))
    }

    @Test
    fun defaultsMatchTheGateway() {
        // EventService.java:9640 — the values the gateway assumes for an unset row.
        assertEquals(18, CarSettingsController.Backlight.DEFAULT_DAY)
        assertEquals(8, CarSettingsController.Backlight.DEFAULT_NIGHT)
    }
}
