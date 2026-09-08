package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The point of these counters is to tell three failures apart that all look like "no data":
 * nothing plugged in, plugged in but CAN-H/CAN-L not wired, and a channel that never opened.
 * The tests below pin each of those apart.
 */
class CanableStatsTest {

    private var now = 0L
    private val stats = CanableStats { now }

    @Test
    fun `a version banner is captured without counting as a frame`() {
        stats.record(SlcanCodec.decode("V1013"))

        assertEquals("V1013", stats.version)
        assertEquals(0, stats.frames)
    }

    @Test
    fun `frames count and group by id`() {
        repeat(3) { stats.record(frame(0x4A5)) }
        stats.record(frame(0x380))

        assertEquals(4, stats.frames)
        assertEquals(listOf(0x4A5 to 3, 0x380 to 1), stats.ids())
    }

    @Test
    fun `a rejected command is counted separately from frames`() {
        stats.record(SlcanEvent.Rejected)

        assertEquals(1, stats.rejected)
        assertEquals(0, stats.frames)
    }

    @Test
    fun `an ack changes no counter`() {
        stats.record(SlcanEvent.Ack)

        assertEquals(0, stats.frames)
        assertEquals(0, stats.rejected)
        assertEquals(0, stats.unparsed)
    }

    @Test
    fun `rate is measured over a full second`() {
        repeat(100) { stats.record(frame(0x123)) }
        now = 1_000

        assertEquals(100, stats.ratePerSec())
    }

    @Test
    fun `rate scales a partial window to a full second`() {
        // 60 frames spread over 1.5 s is 40/s, not 60/s.
        repeat(60) { stats.record(frame(0x123)) }
        now = 1_500

        assertEquals(40, stats.ratePerSec())
    }

    @Test
    fun `distinct count is the bus, not the display slice`() {
        // The head unit reported "16 ids" at 1214 frames/s purely because 16 was the display cap.
        repeat(40) { stats.record(frame(0x300 + it)) }

        assertEquals(40, stats.distinctIds)
    }

    @Test
    fun `the connect banner identifies the adapter`() {
        stats.record(SlcanCodec.decode("16e7497-dirty github.com/normaldotcom/canable2.git"))

        assertEquals("16e7497-dirty github.com/normaldotcom/canable2.git", stats.banner)
        assertNull(stats.version)
    }

    @Test
    fun `the banner does not flap once set`() {
        stats.record(SlcanCodec.decode("16e7497-dirty github.com/normaldotcom/canable2.git"))
        stats.record(SlcanCodec.decode("some later stray line"))

        assertEquals("16e7497-dirty github.com/normaldotcom/canable2.git", stats.banner)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a short stray line is not an identity`() {
        stats.record(SlcanCodec.decode("xy"))

        assertNull(stats.banner)
    }

    @Test
    fun `a decoded frame never becomes a banner`() {
        repeat(5) { stats.record(frame(0x4A5)) }

        assertNull(stats.banner)
        assertNull(stats.version)
    }


    @Test
    fun `an adapter that answers nothing reports nothing`() {
        // Plugged in, channel opened, CAN-H/CAN-L unwired: every counter must stay at zero rather
        // than a stale or invented rate.
        assertNull(stats.version)
        assertEquals(0, stats.frames)
        assertEquals(0, stats.ratePerSec())
    }

    @Test
    fun `rate stays zero before the first window closes`() {
        repeat(500) { stats.record(frame(0x123)) }
        now = 999

        assertEquals(0, stats.ratePerSec())
    }

    @Test
    fun `an unparsed line is not mistaken for a version banner`() {
        stats.record(SlcanCodec.decode("N4A21"))

        assertNull(stats.version)
        assertEquals(1, stats.unparsed)
    }

    private fun frame(id: Int) = SlcanEvent.Received(SlcanFrame(id, listOf(0)))
}
