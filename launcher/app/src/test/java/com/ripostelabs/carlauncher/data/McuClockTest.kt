package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MCU RTC writes the clock only offline and only when the clock is wrong. */
class McuClockTest {

    @Test
    fun `offline and drifted sets the clock`() {
        assertTrue(McuClock.shouldSet(online = false, driftMs = 3_600_000L))
        assertTrue(McuClock.shouldSet(online = false, driftMs = -McuClock.DRIFT_FLOOR_MS - 1))
    }

    @Test
    fun `online never sets the clock`() {
        assertFalse(McuClock.shouldSet(online = true, driftMs = 3_600_000L))
    }

    @Test
    fun `drift inside the floor leaves the clock alone`() {
        assertFalse(McuClock.shouldSet(online = false, driftMs = McuClock.DRIFT_FLOOR_MS))
        assertFalse(McuClock.shouldSet(online = false, driftMs = 0L))
    }
}
