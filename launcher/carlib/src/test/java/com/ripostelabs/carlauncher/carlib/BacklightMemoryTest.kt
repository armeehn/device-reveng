package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendor keeps `Set_Day_Light` / `Set_Night_Light` in its settings provider and replays
 * them in every `2E` (EventService.java:9639-9641). On 0.2 that provider is gone, so the
 * targets live here.
 */
class BacklightMemoryTest {

    private class FakeStore : BacklightMemory.Store {
        val rows = mutableMapOf<String, Int>()
        override fun getInt(key: String, default: Int): Int = rows[key] ?: default
        override fun putInt(key: String, value: Int) { rows[key] = value }
    }

    private val store = FakeStore()
    private val memory = BacklightMemory(store)

    /** setRecordDefaultValue("Set_Day_Light", "20") / ("Set_Night_Light", "8"), :6502-6503. */
    @Test
    fun emptyStoreIsTheVendorDefaultRows() {
        assertEquals(BacklightMemory.Targets(day = 20, night = 8), memory.targets())
    }

    @Test
    fun rememberSurvivesAsRows() {
        memory.remember(day = 15, night = 5)

        assertEquals(BacklightMemory.Targets(day = 15, night = 5), BacklightMemory(store).targets())
    }

    @Test
    fun configCarriesTheRememberedTargets() {
        memory.remember(day = 15, night = 5)

        val config = memory.config(McuOwnerProtocol.StartupConfig(mainVolume = 12))

        assertEquals(15, config.backlightDay)
        assertEquals(5, config.backlightNight)
        assertEquals(12, config.mainVolume)
    }

    /** adjustBLLevel(boolean), :7971-7987: lamps off moves the day target, the night one stays. */
    @Test
    fun levelGoesToTheDaySideWhileLampsAreOff() {
        assertFalse(memory.lampsOn())

        assertEquals(BacklightMemory.Targets(day = 10, night = 8), memory.withLevel(10))
    }

    /** The `71` headlamp bit (:2333-2335, mLAMPConnected) selects the night target instead. */
    @Test
    fun levelGoesToTheNightSideWhileLampsAreOn() {
        memory.onSysEvent(sysEvent(illumination = true))

        assertTrue(memory.lampsOn())
        assertEquals(BacklightMemory.Targets(day = 20, night = 10), memory.withLevel(10))
    }

    @Test
    fun withLevelDoesNotWriteTheStore() {
        memory.withLevel(3)

        assertTrue(store.rows.isEmpty())
    }

    private fun sysEvent(illumination: Boolean) = McuOwnerProtocol.SysEvent(
        disc = false, usb = false, rightTurn = false, illumination = illumination, brake = false,
        reverse = false, accLine = true, mcan = false, startStop = false, hdmi = false, leftTurn = false,
    )
}
