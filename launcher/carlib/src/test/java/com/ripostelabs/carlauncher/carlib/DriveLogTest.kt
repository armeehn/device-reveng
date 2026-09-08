package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.DriveLog.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that exists because of a real wrong answer: a 5.7-minute GPS dropout was
 * interpolated across, and the resulting line correlated beautifully with a field that is not
 * road speed. Every test here is about refusing to let that happen again.
 */
class DriveLogTest {

    private fun sample(
        atMs: Long = 10_000,
        raw017: Int? = 540,
        raw013: Int? = 300,
        refKmh: Double? = 54.0,
        refAt: Long? = 10_000,
    ) = Sample(atMs, raw017, raw013, refKmh, refAt)

    @Test
    fun `a fresh reference at speed is usable`() {
        assertTrue(DriveLog.usableForCalibration(sample()))
        assertEquals("", DriveLog.rejection(sample()))
    }

    /** The exact failure that produced the last wrong answer. */
    @Test
    fun `a stale reference is refused rather than interpolated`() {
        val stale = sample(atMs = 20_000, refAt = 10_000)
        assertFalse(DriveLog.usableForCalibration(stale))
        assertEquals("stale_reference", DriveLog.rejection(stale))
    }

    @Test
    fun `the staleness boundary is exact`() {
        assertTrue(DriveLog.usableForCalibration(sample(atMs = 10_000 + DriveLog.MAX_FIX_AGE_MS, refAt = 10_000)))
        assertFalse(DriveLog.usableForCalibration(sample(atMs = 10_001 + DriveLog.MAX_FIX_AGE_MS, refAt = 10_000)))
    }

    @Test
    fun `no reference at all is refused`() {
        assertEquals("no_reference", DriveLog.rejection(sample(refKmh = null, refAt = null)))
    }

    @Test
    fun `crawling speeds are refused because the reference is too coarse there`() {
        assertEquals("too_slow", DriveLog.rejection(sample(refKmh = 2.0)))
    }

    @Test
    fun `a row with no CAN speed at all is refused`() {
        assertEquals("no_can_speed", DriveLog.rejection(sample(raw017 = null, raw013 = null)))
    }

    /** An unusable row is still written, with its reason — a recorded gap is evidence. */
    @Test
    fun `unusable rows are still recorded, with the reason`() {
        val row = DriveLog.row(sample(atMs = 20_000, refAt = 10_000))
        assertTrue(row.endsWith(",0,stale_reference"))
        assertTrue("the row must name its reference source", row.contains("OBD_PID_0D"))
        assertTrue(row.contains("10000"))
    }

    @Test
    fun `a usable row is marked usable and carries no reason`() {
        assertTrue(DriveLog.row(sample()).endsWith(",1,"))
    }

    @Test
    fun `the header matches the row shape`() {
        assertEquals(DriveLog.HEADER.split(",").size, DriveLog.row(sample()).split(",").size)
    }

    /** Two points fit any line. The previous wrong scale rested on exactly that. */
    @Test
    fun `too few points produce no scale at all`() {
        val few = (1..DriveLog.MIN_POINTS - 1).map {
            sample(atMs = it * 1000L, refAt = it * 1000L, raw017 = it * 10, refKmh = it * 1.0)
        }
        assertNull(DriveLog.scaleKmhPerLsb(few) { it.raw017 })
    }

    @Test
    fun `enough clean points recover the scale`() {
        val pts = (1..12).map {
            val raw = it * 100
            sample(atMs = it * 1000L, refAt = it * 1000L, raw017 = raw, refKmh = raw * 0.1)
        }
        val scale = DriveLog.scaleKmhPerLsb(pts) { it.raw017 }!!
        assertEquals(0.1, scale, 1e-9)
    }

    /** Stale rows must not influence the fit even when they are numerically tempting. */
    @Test
    fun `stale rows are excluded from the fit`() {
        val good = (1..12).map {
            val raw = it * 100
            sample(atMs = it * 1000L, refAt = it * 1000L, raw017 = raw, refKmh = raw * 0.1)
        }
        val poison = (1..12).map {
            sample(atMs = 900_000L + it, refAt = 1L, raw017 = 100, refKmh = 200.0)
        }
        assertEquals(0.1, DriveLog.scaleKmhPerLsb(good + poison) { it.raw017 }!!, 1e-9)
    }

    /** One long cruise at a single speed is not a calibration, however many rows it has. */
    @Test
    fun `a single speed band is visible as a single band`() {
        val oneSpeed = (1..50).map { sample(atMs = it * 1000L, refAt = it * 1000L) }
        assertEquals(1, DriveLog.distinctSpeedBands(oneSpeed))

        val four = listOf(20.0, 40.0, 60.0, 80.0).flatMapIndexed { i, kmh ->
            (1..5).map { sample(atMs = (i * 10 + it) * 1000L, refAt = (i * 10 + it) * 1000L, refKmh = kmh) }
        }
        assertEquals(4, DriveLog.distinctSpeedBands(four))
    }
}
