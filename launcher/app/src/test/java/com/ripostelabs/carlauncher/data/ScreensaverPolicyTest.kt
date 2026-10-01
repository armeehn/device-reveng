package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAV4-201: when the launcher's own screensaver may cover the panel. */
class ScreensaverPolicyTest {

    private val minute = 60_000L

    // Idle past a one-minute timeout, parked, ACC on, nothing else going on.
    private val idle = SaverInputs(
        timeoutS = 60,
        idleMs = 2 * minute,
        accOn = true,
        reverse = false,
        callUp = false,
        moving = false,
    )

    @Test
    fun `idle past the timeout starts it`() {
        assertTrue(ScreensaverPolicy.shouldShow(idle))
    }

    @Test
    fun `not yet idle long enough`() {
        assertFalse(ScreensaverPolicy.shouldShow(idle.copy(idleMs = minute - 1)))
        assertTrue(ScreensaverPolicy.shouldShow(idle.copy(idleMs = minute)))
    }

    @Test
    fun `never means never`() {
        assertFalse(ScreensaverPolicy.shouldShow(idle.copy(timeoutS = 0, idleMs = 99 * minute)))
    }

    @Test
    fun `reverse, a call, driving or ACC off each keep it away`() {
        assertFalse(ScreensaverPolicy.shouldShow(idle.copy(reverse = true)))
        assertFalse(ScreensaverPolicy.shouldShow(idle.copy(callUp = true)))
        assertFalse(ScreensaverPolicy.shouldShow(idle.copy(moving = true)))
        // ACC off is standby engaging: the panel is about to go dark, not to a clock.
        assertFalse(ScreensaverPolicy.shouldShow(idle.copy(accOn = false)))
    }

    @Test
    fun `the idle clock restarts on activity`() {
        var now = 1_000L
        val clock = IdleClock { now }

        now += 5 * minute
        assertEquals(5 * minute, clock.idleMs())

        clock.touch()
        now += 10_000L
        assertEquals(10_000L, clock.idleMs())
    }

    @Test
    fun `the face drifts inside its margin, a new spot each minute`() {
        val a = ScreensaverPolicy.drift(minuteOfDay = 0, range = 100)
        val b = ScreensaverPolicy.drift(minuteOfDay = 1, range = 100)

        assertTrue(a.first in -100..100 && a.second in -100..100)
        assertTrue(a != b)
        assertEquals(a, ScreensaverPolicy.drift(minuteOfDay = 0, range = 100))
    }
}
