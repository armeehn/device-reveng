package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MCU RTC writes the clock only offline, before GPS, and only when the clock is wrong. */
class McuClockTest {

    @Test
    fun `offline and drifted sets the clock`() {
        assertTrue(McuClock.shouldSet(online = false, gpsSet = false, driftMs = 3_600_000L))
        assertTrue(McuClock.shouldSet(online = false, gpsSet = false, driftMs = -McuClock.DRIFT_FLOOR_MS - 1))
    }

    @Test
    fun `online never sets the clock`() {
        assertFalse(McuClock.shouldSet(online = true, gpsSet = false, driftMs = 3_600_000L))
    }

    @Test
    fun `a GPS-set clock outranks the MCU RTC`() {
        assertFalse(McuClock.shouldSet(online = false, gpsSet = true, driftMs = 3_600_000L))
    }

    @Test
    fun `pushes the clock to the MCU once, online, with a live year`() {
        assertTrue(McuClock.shouldPush(online = true, gpsSet = false, year = 2026, alreadyPushed = false))
        assertFalse(McuClock.shouldPush(online = false, gpsSet = false, year = 2026, alreadyPushed = false))
        assertFalse(McuClock.shouldPush(online = true, gpsSet = false, year = 2024, alreadyPushed = false))
        assertFalse(McuClock.shouldPush(online = true, gpsSet = false, year = 2026, alreadyPushed = true))
    }

    @Test
    fun `pushes offline once GPS set the clock`() {
        assertTrue(McuClock.shouldPush(online = false, gpsSet = true, year = 2026, alreadyPushed = false))
        assertFalse(McuClock.shouldPush(online = false, gpsSet = true, year = 2024, alreadyPushed = false))
        assertFalse(McuClock.shouldPush(online = false, gpsSet = true, year = 2026, alreadyPushed = true))
    }

    @Test
    fun `drift inside the floor leaves the clock alone`() {
        assertFalse(McuClock.shouldSet(online = false, gpsSet = false, driftMs = McuClock.DRIFT_FLOOR_MS))
        assertFalse(McuClock.shouldSet(online = false, gpsSet = false, driftMs = 0L))
    }
}
