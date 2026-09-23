package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The panel DIM key (code 246, EventService.java:644 / :2521) is `ProccessDIMKey` (:7909-7942):
 * the level steps 3 → 12 → 20 → 3, the step lands on the target the panel is showing, both rows
 * are written and one `2E` goes out.
 */
class DimKeyTest {

    private class FakeStore : BacklightMemory.Store {
        val rows = mutableMapOf<String, Int>()
        override fun getInt(key: String, default: Int): Int = rows[key] ?: default
        override fun putInt(key: String, value: Int) { rows[key] = value }
    }

    private val memory = BacklightMemory(FakeStore())
    private val pushed = mutableListOf<BacklightMemory.Targets>()
    private val dim = DimKey(memory) { day, night -> pushed += BacklightMemory.Targets(day, night) }

    /** initBLLevel (:7947-7957): ≤6 is low, ≤12 mid, else high; the day row starts at 20 = high. */
    @Test
    fun cyclesLowMidHighOnTheDaySide() {
        dim.onKey(McuOwnerProtocol.Key.DIM)
        dim.onKey(McuOwnerProtocol.Key.DIM)
        dim.onKey(McuOwnerProtocol.Key.DIM)

        assertEquals(
            listOf(
                BacklightMemory.Targets(day = 3, night = 8),
                BacklightMemory.Targets(day = 12, night = 8),
                BacklightMemory.Targets(day = 20, night = 8),
            ),
            pushed,
        )
    }

    @Test
    fun stepsTheNightSideWhileLampsAreOn() {
        memory.onSysEvent(sysEvent(illumination = true))

        dim.onKey(McuOwnerProtocol.Key.DIM)

        // Night row 8 is mid, so the step is to high.
        assertEquals(listOf(BacklightMemory.Targets(day = 20, night = 20)), pushed)
    }

    @Test
    fun otherKeysDoNothing() {
        dim.onKey(McuOwnerProtocol.Key.MUTE)

        assertEquals(emptyList<BacklightMemory.Targets>(), pushed)
    }

    private fun sysEvent(illumination: Boolean) = McuOwnerProtocol.SysEvent(
        disc = false, usb = false, rightTurn = false, illumination = illumination, brake = false,
        reverse = false, accLine = true, mcan = false, startStop = false, hdmi = false, leftTurn = false,
    )
}
