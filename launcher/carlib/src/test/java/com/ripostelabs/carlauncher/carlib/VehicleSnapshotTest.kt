package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Companion.fold
import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the folding and, more importantly, the two rules that make this type safe to render:
 * a missing reading is null rather than zero, and a field goes absent once it is stale.
 */
class VehicleSnapshotTest {

    private val empty = VehicleSnapshot()

    private fun info(rpm: Int, coolant: Int?) =
        CanSignal.VehicleInfo(rpm = rpm, speedRaw = 0, speedKmh = 0.0, coolantC = coolant)

    @Test
    fun `folding a signal makes its fields readable`() {
        val s = empty.fold(info(2500, 90), atMs = 1_000)
        assertEquals(2500, s.rpm)
        assertEquals(90, s.coolantC)
    }

    /** A field the car never reported must read null, never a plausible-looking zero. */
    @Test
    fun `an unseen field is null, not zero`() {
        assertNull(empty.rpm)
        assertNull(empty.coolantC)
        assertNull(empty.reverse)
        assertEquals(listOf(null, null, null, null), empty.tyresKpa)
    }

    /** A signal that carries no coolant must not blank an earlier good reading with a zero. */
    @Test
    fun `a null inside a signal leaves the previous value alone`() {
        val s = empty.fold(info(2000, 88), 1_000).fold(info(2100, null), 1_100)
        assertEquals(2100, s.rpm)
        assertEquals(88, s.coolantC)
    }

    @Test
    fun `a field goes absent once it is stale`() {
        val s = empty.fold(info(3000, 90), atMs = 1_000)
        val justInside = 1_000 + VehicleSnapshot.STALE_AFTER_MS
        assertEquals(3000, s.int(Field.RPM, now = justInside))
        assertNull(s.int(Field.RPM, now = justInside + 1))
    }

    /** Staleness is per field: a slow signal must not be blanked by a fast one, or vice versa. */
    @Test
    fun `a fresh field survives while a stale one disappears`() {
        val tpms = CanSignal.Tpms(220, 220, 210, 210, null)
        val s = empty.fold(tpms, atMs = 1_000).fold(info(2000, 90), atMs = 20_000)
        assertEquals(2000, s.int(Field.RPM, now = 20_000))
        assertNull(s.int(Field.TYRE_FL_KPA, now = 20_000))
    }

    @Test
    fun `a dead tyre sensor shows a gap rather than shifting the others`() {
        val s = empty.fold(CanSignal.Tpms(220, null, 210, 205, null), atMs = 1_000)
        assertEquals(listOf(220, null, 210, 205), s.tyresKpa)
    }

    /**
     * Speed must not reach the snapshot. A drive proved 0x32 is not road speed and the other two
     * are unconfirmed candidates; folding either would let a screen present a guess as fact.
     */
    @Test
    fun `speed candidates are deliberately not folded in`() {
        val s = empty.fold(CanSignal.SpeedCandidate(source = "0x17", raw = 540, kmh = 54.0), atMs = 1_000)
        assertFalse(s.hasAnySource(now = 1_000))
    }

    @Test
    fun `reverse and side cameras fold as booleans`() {
        val s = empty
            .fold(CanSignal.SysEvent(reverseRaw = true, discPresent = false, usbPresent = false, raw = 2), 1_000)
            .fold(CanSignal.SideCamera(left = true, right = false, leftForced = false), 1_000)
        assertEquals(true, s.reverse)
        assertEquals(true, s.bool(Field.SIDE_CAMERA_LEFT, now = 1_000))
        assertEquals(false, s.bool(Field.SIDE_CAMERA_RIGHT, now = 1_000))
    }

    @Test
    fun `nearest object takes the closest of either end and ignores empty sensors`() {
        val radar = CanSignal.ParkingRadar(
            rearCm = listOf(90, null, null, null),
            frontCm = listOf(null, 60, null, null),
        )
        assertEquals(60, empty.fold(radar, 1_000).nearestObjectCm)
    }

    @Test
    fun `nothing detected reports no distance rather than zero`() {
        val radar = CanSignal.ParkingRadar(rearCm = List(4) { null }, frontCm = List(4) { null })
        assertNull(empty.fold(radar, 1_000).nearestObjectCm)
    }

    /** With no live field there is no car to draw — a screen needs to be able to ask that. */
    @Test
    fun `hasAnySource distinguishes no car from a car reading zero`() {
        assertFalse(empty.hasAnySource(now = 1_000))
        val s = empty.fold(info(0, null), atMs = 1_000)
        assertTrue(s.hasAnySource(now = 1_000))
        assertFalse(s.hasAnySource(now = 1_000 + VehicleSnapshot.STALE_AFTER_MS + 1))
    }
}
