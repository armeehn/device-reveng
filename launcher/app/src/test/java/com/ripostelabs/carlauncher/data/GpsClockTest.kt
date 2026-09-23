package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** GPS writes the clock once per wake, from a real fix, and only when the clock is wrong. */
class GpsClockTest {

    private val dayMs = 24L * 3_600_000L
    private val live = LocalDateTime.of(2026, 9, 22, 17, 0).toEpochSecond(ZoneOffset.UTC) * 1_000L

    @Test
    fun `a fix with four satellites and a drifted clock sets it`() {
        assertTrue(GpsClock.shouldSet(satsInFix = 4, gpsTimeMs = live, driftMs = dayMs, alreadySet = false))
        assertTrue(GpsClock.shouldSet(satsInFix = 9, gpsTimeMs = live, driftMs = -dayMs, alreadySet = false))
    }

    @Test
    fun `three satellites is no fix`() {
        assertFalse(GpsClock.shouldSet(satsInFix = 3, gpsTimeMs = live, driftMs = dayMs, alreadySet = false))
    }

    @Test
    fun `sets once per wake`() {
        assertFalse(GpsClock.shouldSet(satsInFix = 4, gpsTimeMs = live, driftMs = dayMs, alreadySet = true))
    }

    @Test
    fun `a receiver still on its default date is ignored`() {
        val stale = LocalDateTime.of(2014, 12, 31, 23, 59).toEpochSecond(ZoneOffset.UTC) * 1_000L
        assertFalse(GpsClock.shouldSet(satsInFix = 4, gpsTimeMs = stale, driftMs = dayMs, alreadySet = false))
    }

    @Test
    fun `a time past the 32-bit epoch is ignored`() {
        val past2038 = (Int.MAX_VALUE.toLong() + 1L) * 1_000L
        assertFalse(GpsClock.shouldSet(satsInFix = 4, gpsTimeMs = past2038, driftMs = dayMs, alreadySet = false))
    }

    @Test
    fun `drift inside the floor leaves the clock alone`() {
        assertFalse(GpsClock.shouldSet(satsInFix = 4, gpsTimeMs = live, driftMs = McuClock.DRIFT_FLOOR_MS, alreadySet = false))
        assertFalse(GpsClock.shouldSet(satsInFix = 4, gpsTimeMs = live, driftMs = 0L, alreadySet = false))
    }
}
