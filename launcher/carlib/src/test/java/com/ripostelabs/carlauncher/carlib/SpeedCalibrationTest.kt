package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last wrong speed this project shipped rested on a two-point fit across a GPS dropout, so
 * most of this file is about the fits that must NOT come out: no reference, one speed band, a
 * stale reference, a standstill. The positive case is a synthetic drive with known scales, and
 * the fit has to recover them exactly, because the data is exactly linear.
 */
class SpeedCalibrationTest {

    private fun c017(raw: Int) = CanSignal.SpeedCandidate(source = "0x17", raw = raw, kmh = 0.0)
    private fun c013(raw: Int) = CanSignal.TripInfo(rangeToEmptyKm = null, speedCandidateRaw = raw)

    /**
     * A drive from [from] to [to] km/h in 2 km/h steps, one reference per step, both candidates.
     *
     * Steps are spaced past MAX_FIX_AGE_MS on purpose. Within that window a sample minted by one
     * candidate also carries the other, and the other is then from the PREVIOUS step — a real and
     * acceptable pairing on a road (transients pair within two seconds) but not a bit-exact one,
     * and this drive is used to assert exactness.
     */
    private fun drive(cal: SpeedCalibration, from: Int, to: Int, t0: Long = 0L): Long {
        var t = t0
        for (kmh in from..to step 2) {
            t += DriveLog.MAX_FIX_AGE_MS + 500
            cal.onReference(kmh, t)
            cal.onCandidate(c013(kmh * 7), t + 10)
            cal.onCandidate(c017(kmh * 10), t + 20)
        }
        return t
    }

    @Test
    fun `a linear drive recovers both scales exactly`() {
        val cal = SpeedCalibration()
        drive(cal, from = 6, to = 60)

        val fit = cal.fit()

        assertNotNull(fit)
        assertEquals(0.1, fit!!.scale017!!, 1e-9)
        assertEquals(1.0 / 7.0, fit.scale013!!, 1e-9)
        assertTrue(fit.usable >= DriveLog.MIN_POINTS)
        assertTrue(fit.bands >= 2)
    }

    @Test
    fun `each candidate carries the other if it is fresh`() {
        val cal = SpeedCalibration()
        cal.onReference(40, 1_000)
        cal.onCandidate(c017(400), 1_010)
        cal.onCandidate(c013(280), 1_020)

        val last = cal.samples().last()
        assertEquals(400, last.raw017)
        assertEquals(280, last.raw013)
    }

    @Test
    fun `csv starts with the header and has one row per sample`() {
        val cal = SpeedCalibration()
        drive(cal, from = 10, to = 14)

        val lines = cal.csv().lines()
        assertEquals(DriveLog.HEADER, lines.first())
        assertEquals(cal.sampleCount() + 1, lines.size)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `no reference means no fit, and the samples say why`() {
        val cal = SpeedCalibration()
        var t = 0L
        repeat(20) { t += 100; cal.onCandidate(c013(300), t) }

        assertNull(cal.fit())
        assertTrue(cal.samples().all { DriveLog.rejection(it) == "no_reference" })
    }

    @Test
    fun `one speed band is a cluster, not a calibration`() {
        val cal = SpeedCalibration()
        // Plenty of points, every one between 30 and 39 km/h.
        var t = 0L
        repeat(20) { t += 500; cal.onReference(32 + (it % 4), t); cal.onCandidate(c013(220), t + 10) }

        assertNull(cal.fit())
    }

    @Test
    fun `a stale reference does not anchor a sample`() {
        val cal = SpeedCalibration()
        cal.onReference(40, 1_000)

        // The candidate arrives well after the reference has aged out.
        cal.onCandidate(c013(280), 1_000 + DriveLog.MAX_FIX_AGE_MS + 1)

        assertEquals("stale_reference", DriveLog.rejection(cal.samples().last()))
    }

    @Test
    fun `a stale other-candidate is not attached`() {
        val cal = SpeedCalibration()
        cal.onReference(40, 1_000)
        cal.onCandidate(c017(400), 1_010)

        // Forty seconds later 0x13 arrives. The old 0x17 is not evidence about this instant.
        cal.onCandidate(c013(280), 41_000)

        assertNull(cal.samples().last().raw017)
        assertEquals(280, cal.samples().last().raw013)
    }

    @Test
    fun `a standstill is excluded rather than fitted through`() {
        val cal = SpeedCalibration()
        var t = 0L
        // Twenty samples at 0 km/h: below MIN_USABLE_KMH, every one is "too_slow".
        repeat(20) { t += 500; cal.onReference(0, t); cal.onCandidate(c013(0), t + 10) }

        assertNull(cal.fit())
        assertTrue(cal.samples().all { DriveLog.rejection(it) == "too_slow" })
    }

    @Test
    fun `fewer than the minimum points is no fit even with range`() {
        val cal = SpeedCalibration()
        // Three references at 10, 30, 50 km/h: range, but far too few points.
        var t = 0L
        for (kmh in listOf(10, 30, 50)) { t += 500; cal.onReference(kmh, t); cal.onCandidate(c013(kmh * 7), t + 10) }

        assertNull(cal.fit())
    }

    @Test
    fun `a signal that is not a speed mints nothing`() {
        val cal = SpeedCalibration()
        cal.onReference(40, 1_000)

        cal.onCandidate(CanSignal.SysEvent(reverseRaw = false, discPresent = false, usbPresent = false, raw = 0), 1_010)

        assertEquals(0, cal.sampleCount())
    }

    @Test
    fun `a candidate from the disproved field is ignored`() {
        val cal = SpeedCalibration()
        cal.onReference(40, 1_000)

        // 0x32 was proved not to be road speed. It must not enter the log as a candidate.
        cal.onCandidate(CanSignal.SpeedCandidate(source = "0x32", raw = 123, kmh = 0.0), 1_010)

        assertEquals(0, cal.sampleCount())
    }

    @Test
    fun `the log is bounded and drops the oldest`() {
        val cal = SpeedCalibration(maxSamples = 5)
        var t = 0L
        repeat(10) { t += 100; cal.onCandidate(c013(it), t) }

        assertEquals(5, cal.sampleCount())
        assertEquals(5, cal.samples().first().raw013)
        assertEquals(9, cal.samples().last().raw013)
    }
}
