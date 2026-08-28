package com.reveng.carlauncher.carlib

import com.reveng.carlauncher.carlib.CarEvents.Companion.MOVING_ABOVE_KMH
import com.reveng.carlauncher.carlib.CarEvents.Companion.PARKED_BELOW_KMH
import com.reveng.carlauncher.carlib.CarEvents.Companion.nextMotion
import com.reveng.carlauncher.carlib.CarEvents.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v2.5 parked-only gate reads [CarEvents.motion], and [CarEvents.nextMotion] is the whole of
 * that decision. Getting it wrong is a safety defect in both directions: a false PARKED hands the
 * driver a keyboard at 60 km/h, a false MOVING locks them out of their own launcher in a garage.
 *
 * A hysteresis band is also the kind of logic that reads correct and behaves wrong, so these
 * assertions pin both thresholds, both of their edges, and the no-flap property — not just one
 * sample speed per state.
 */
class MotionHysteresisTest {

    /** A speed strictly inside the band, where neither threshold fires. */
    private val inBandKmh = (PARKED_BELOW_KMH + MOVING_ABOVE_KMH) / 2

    @Test
    fun bandHasRealGap() {
        // With PARKED >= MOVING the band collapses and every case below passes vacuously.
        assertTrue(PARKED_BELOW_KMH < MOVING_ABOVE_KMH)
        assertTrue(inBandKmh > PARKED_BELOW_KMH)
        assertTrue(inBandKmh < MOVING_ABOVE_KMH)
    }

    @Test
    fun negativeSpeedIsUnknown() {
        val noFix = GpsSpeedSource.SPEED_UNKNOWN

        assertEquals(Motion.UNKNOWN, nextMotion(Motion.UNKNOWN, noFix))
        assertEquals(Motion.UNKNOWN, nextMotion(Motion.PARKED, noFix))
        // A dropout must wipe a MOVING verdict too — the car may have parked while we were blind.
        assertEquals(Motion.UNKNOWN, nextMotion(Motion.MOVING, noFix))
    }

    @Test
    fun firstBandReadingIsMoving() {
        // Band entry from UNKNOWN: we have a live fix above walking pace, so the car is not
        // stationary and the gate closes. Resolving to PARKED here would open it on a rolling car.
        assertEquals(Motion.MOVING, nextMotion(Motion.UNKNOWN, inBandKmh))
    }

    @Test
    fun firstStandstillIsParked() {
        assertEquals(Motion.PARKED, nextMotion(Motion.UNKNOWN, 0))
    }

    @Test
    fun movingThresholdInclusive() {
        assertEquals(Motion.MOVING, nextMotion(Motion.PARKED, MOVING_ABOVE_KMH))
        assertEquals(Motion.PARKED, nextMotion(Motion.PARKED, MOVING_ABOVE_KMH - 1))
    }

    @Test
    fun parkedThresholdInclusive() {
        assertEquals(Motion.PARKED, nextMotion(Motion.MOVING, PARKED_BELOW_KMH))
        assertEquals(Motion.MOVING, nextMotion(Motion.MOVING, PARKED_BELOW_KMH + 1))
    }

    @Test
    fun bandHoldsParked() {
        assertEquals(Motion.PARKED, nextMotion(Motion.PARKED, inBandKmh))
    }

    @Test
    fun bandHoldsMoving() {
        assertEquals(Motion.MOVING, nextMotion(Motion.MOVING, inBandKmh))
    }

    @Test
    fun crawlNeverFlaps() {
        // A car crawling in traffic sweeps the whole band, repeatedly. Whatever verdict it
        // entered the band with must survive the entire sweep — this is why the band exists.
        val up = (PARKED_BELOW_KMH + 1) until MOVING_ABOVE_KMH
        val crawl = up + up.reversed()

        var parked = Motion.PARKED
        var moving = Motion.MOVING
        crawl.forEach { kmh ->
            parked = nextMotion(parked, kmh)
            moving = nextMotion(moving, kmh)
        }

        assertEquals(Motion.PARKED, parked)
        assertEquals(Motion.MOVING, moving)
    }

    @Test
    fun pullAwayAndStop() {
        // Indices below are into the runningFold output, which is offset by one (seed first).
        val trip = listOf(0, 2, 4, 6, 9, 40, 90, 40, 9, 5, 2, 0)
        val seen = trip.runningFold(Motion.UNKNOWN) { state, kmh -> nextMotion(state, kmh) }

        assertEquals(Motion.PARKED, seen[1])  // 0 km/h — standstill
        assertEquals(Motion.PARKED, seen[4])  // 6 km/h — inside the band, still parked
        assertEquals(Motion.MOVING, seen[5])  // 9 km/h — threshold crossed

        assertEquals(Motion.MOVING, seen[10]) // 5 km/h — back inside the band, still moving
        assertEquals(Motion.PARKED, seen[11]) // 2 km/h — at or below the parked line
        assertEquals(Motion.PARKED, seen.last())
    }
}
