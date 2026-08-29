package com.reveng.carlauncher.carlib

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RootSession] used to set its "no `su`" flag permanently on the first failed start, so a Magisk
 * prompt denied or timed out during launcher startup cost the persistent channel for the whole
 * process — every later SysVar write went back to a fresh `su` fork. The flag now expires.
 *
 * The clock is injected, so this runs on a plain JVM with no SystemClock.
 */
class RetryLatchTest {

    private var now = 0L
    private val latch = RetryLatch { now }

    @Test
    fun `starts unlatched`() {
        assertFalse(latch.isLatched())
    }

    @Test
    fun `latches on failure`() {
        latch.latch()

        assertTrue(latch.isLatched())
    }

    @Test
    fun `stays latched for the whole retry window`() {
        latch.latch()
        now += RetryLatch.RETRY_AFTER_MS - 1

        assertTrue(latch.isLatched())
    }

    @Test
    fun `expires once the retry window has passed`() {
        latch.latch()
        now += RetryLatch.RETRY_AFTER_MS

        assertFalse(latch.isLatched())
    }

    @Test
    fun `a second failure re-arms the window from that moment`() {
        latch.latch()
        now += RetryLatch.RETRY_AFTER_MS
        latch.latch()
        now += RetryLatch.RETRY_AFTER_MS - 1

        assertTrue(latch.isLatched())
    }

    @Test
    fun `clear allows an immediate retry`() {
        latch.latch()
        latch.clear()

        assertFalse(latch.isLatched())
    }
}
