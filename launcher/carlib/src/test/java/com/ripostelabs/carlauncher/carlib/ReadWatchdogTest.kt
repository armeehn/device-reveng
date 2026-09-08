package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watchdog exists to tell an unplugged adapter from a parked car, and the negative controls
 * matter more than the positive one: a watchdog that fires on ordinary quiet would tear down a
 * working link every time the vehicle sat still.
 */
class ReadWatchdogTest {

    @Test
    fun `silence past the patience asks for a check`() {
        val watchdog = ReadWatchdog(patience = 3)

        repeat(3) { watchdog.record(0) }

        assertTrue(watchdog.shouldVerifyDevice())
    }

    @Test
    fun `a failed read counts the same as an empty one`() {
        val watchdog = ReadWatchdog(patience = 2)

        watchdog.record(-1)
        watchdog.record(-1)

        assertTrue(watchdog.shouldVerifyDevice())
    }

    @Test
    fun `asking resets the count so checks are periodic`() {
        val watchdog = ReadWatchdog(patience = 2)

        repeat(2) { watchdog.record(0) }
        assertTrue(watchdog.shouldVerifyDevice())

        // Immediately after a check, silence starts accumulating again from zero.
        assertFalse(watchdog.shouldVerifyDevice())
        watchdog.record(0)
        assertFalse(watchdog.shouldVerifyDevice())
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `silence below the patience asks for nothing`() {
        val watchdog = ReadWatchdog(patience = 5)

        repeat(4) { watchdog.record(0) }

        assertFalse(watchdog.shouldVerifyDevice())
    }

    @Test
    fun `one byte anywhere in the run clears the suspicion`() {
        // A car that is quiet, then emits a single frame, then goes quiet again must never be
        // treated as an unplugged adapter.
        val watchdog = ReadWatchdog(patience = 3)

        watchdog.record(0)
        watchdog.record(0)
        watchdog.record(12)
        watchdog.record(0)
        watchdog.record(0)

        assertFalse(watchdog.shouldVerifyDevice())
    }

    @Test
    fun `a busy link never asks for a check`() {
        val watchdog = ReadWatchdog(patience = 2)

        repeat(100) { watchdog.record(64) }

        assertFalse(watchdog.shouldVerifyDevice())
    }
}
