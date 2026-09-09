package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.CarEvents.Companion.BUS_SPEED_STALE_MS
import com.ripostelabs.carlauncher.carlib.CarEvents.Companion.MOVING_ABOVE_KMH
import com.ripostelabs.carlauncher.carlib.CarEvents.Companion.PARKED_BELOW_KMH
import com.ripostelabs.carlauncher.carlib.CarEvents.Companion.nextMotion
import com.ripostelabs.carlauncher.carlib.CarEvents.Companion.pickSpeed
import com.ripostelabs.carlauncher.carlib.CarEvents.Motion
import com.ripostelabs.carlauncher.carlib.CarEvents.SpeedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The raw body bus became the safety gate's speed source on 2026-09-09, after a drive paired it
 * against the car's own ECU (OBD PID 0x0D) across 0..60 km/h on four independent ids.
 *
 * Getting the arbitration wrong is a safety defect, and it fails in two directions: trusting the
 * WRONG source hands the driver a keyboard at speed, and dropping a good source locks them out of
 * their own launcher. So these pin the priority, the staleness edges and — most importantly — that
 * the disproved MCU digest can never win.
 */
class BusSpeedGateTest {

    private val noReading = GpsSpeedSource.SPEED_UNKNOWN
    private val fresh = 0L
    private val stale = BUS_SPEED_STALE_MS

    @Test
    fun `a fresh bus speed outranks GPS`() {
        val (kmh, source) = pickSpeed(
            canKmh = noReading, canAgeMs = Long.MAX_VALUE, gpsKmh = 40,
            busKmh = 42, busAgeMs = fresh,
        )

        assertEquals(42, kmh)
        assertEquals(SpeedSource.BUS, source)
    }

    /** Unplug the adapter mid-drive and the gate must fall back, not hold the last number. */
    @Test
    fun `a stale bus speed yields to GPS`() {
        val (kmh, source) = pickSpeed(
            canKmh = noReading, canAgeMs = Long.MAX_VALUE, gpsKmh = 40,
            busKmh = 42, busAgeMs = stale,
        )

        assertEquals(40, kmh)
        assertEquals(SpeedSource.GPS, source)
    }

    /** The boundary itself: at exactly the limit the reading is already stale. */
    @Test
    fun `the staleness edge is exclusive`() {
        val (_, justFresh) = pickSpeed(
            canKmh = noReading, canAgeMs = Long.MAX_VALUE, gpsKmh = noReading,
            busKmh = 42, busAgeMs = BUS_SPEED_STALE_MS - 1,
        )
        assertEquals(SpeedSource.BUS, justFresh)

        val (_, justStale) = pickSpeed(
            canKmh = noReading, canAgeMs = Long.MAX_VALUE, gpsKmh = noReading,
            busKmh = 42, busAgeMs = BUS_SPEED_STALE_MS,
        )
        assertEquals(SpeedSource.NONE, justStale)
    }

    /**
     * The MCU digest speed byte was disproved by a drive and must stay out of the gate even when
     * it is the only reading present. This is the assertion that stops it being quietly promoted.
     */
    @Test
    fun `the untrusted MCU digest never wins`() {
        val (kmh, source) = pickSpeed(
            canKmh = 99, canAgeMs = fresh, gpsKmh = noReading,
            busKmh = noReading, busAgeMs = Long.MAX_VALUE,
        )

        assertNotEquals(SpeedSource.CAN, source)
        assertEquals(noReading, kmh)
        assertEquals(SpeedSource.NONE, source)
    }

    /** With the bus live, the digest loses even while fresh and even if someone flips its flag. */
    @Test
    fun `the bus outranks the digest under either trust setting`() {
        listOf(false, true).forEach { trusted ->
            val (kmh, source) = pickSpeed(
                canKmh = 99, canAgeMs = fresh, gpsKmh = 40, trusted = trusted,
                busKmh = 42, busAgeMs = fresh,
            )

            assertEquals(42, kmh)
            assertEquals(SpeedSource.BUS, source)
        }
    }

    /** Nothing anywhere is UNKNOWN, which fails open — the documented, deliberate choice. */
    @Test
    fun `no source at all leaves motion unknown`() {
        val (kmh, source) = pickSpeed(
            canKmh = noReading, canAgeMs = Long.MAX_VALUE, gpsKmh = noReading,
            busKmh = noReading, busAgeMs = Long.MAX_VALUE,
        )

        assertEquals(SpeedSource.NONE, source)
        assertEquals(Motion.UNKNOWN, nextMotion(Motion.PARKED, kmh))
    }

    /**
     * End to end through the gate: the case the raw bus was wired for. In a garage GPS has no fix,
     * so before this change the verdict was UNKNOWN and the gate failed open while the car rolled.
     */
    @Test
    fun `a moving car with no GPS fix now reads as moving`() {
        val (kmh, source) = pickSpeed(
            canKmh = noReading, canAgeMs = Long.MAX_VALUE, gpsKmh = noReading,
            busKmh = MOVING_ABOVE_KMH + 1, busAgeMs = fresh,
        )

        assertEquals(SpeedSource.BUS, source)
        assertEquals(Motion.MOVING, nextMotion(Motion.UNKNOWN, kmh))
    }

    /** And the opposite: genuinely stopped on the bus must release the gate. */
    @Test
    fun `a stopped car on the bus reads as parked`() {
        val (kmh, source) = pickSpeed(
            canKmh = noReading, canAgeMs = Long.MAX_VALUE, gpsKmh = noReading,
            busKmh = PARKED_BELOW_KMH, busAgeMs = fresh,
        )

        assertEquals(SpeedSource.BUS, source)
        assertEquals(Motion.PARKED, nextMotion(Motion.MOVING, kmh))
    }
}
