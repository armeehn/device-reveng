package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins 0x13 (vehicle information page) at the decoder level. The range decode was audited
 * against the vendor parser in 2026-08 and never had a test; the trip fields are new and rest
 * on that same parser (`OnHandleCanVehicleInformationPageCmd`): every word is
 * `computeValue(bArr[i+1], bArr[i])` = big-endian, `bArr[i]` = `p[i-2]`, 0xFFFF = no data.
 *
 * Byte order is pinned with asymmetric values on purpose: a flipped word still yields a
 * plausible number, which is how earlier decodes went wrong unnoticed.
 */
class HiworldTripInfoTest {

    private fun trip(vararg pairs: Pair<Int, Int>): CanSignal.TripInfo {
        val p = ByteArray(12)
        pairs.forEach { (i, v) -> p[i] = v.toByte() }
        return HiworldCanDecoder.decodePayload(0x13, p) as CanSignal.TripInfo
    }

    /** OEM: range = computeValue(bArr[5], bArr[4]) => (p[2] shl 8) or p[3]. */
    @Test
    fun `range is big-endian across p2 and p3`() {
        assertEquals(300, trip(2 to 0x01, 3 to 0x2C).rangeToEmptyKm)
    }

    @Test
    fun `range sentinel means no data`() {
        assertNull(trip(2 to 0xFF, 3 to 0xFF).rangeToEmptyKm)
    }

    /** OEM: bArr[13] = p[11] == 1 => "mile". The snapshot field is km, so convert. */
    @Test
    fun `range in miles is converted to km`() {
        assertEquals(161, trip(2 to 0x00, 3 to 100, 11 to 1).rangeToEmptyKm)
        assertEquals(100, trip(2 to 0x00, 3 to 100, 11 to 0).rangeToEmptyKm)
    }

    /** OEM: elapsed = computeValue(bArr[9], bArr[8]) => p[6:7], minutes. */
    @Test
    fun `elapsed time is big-endian across p6 and p7`() {
        assertEquals(0x0105, trip(6 to 0x01, 7 to 0x05).elapsedMin)
        assertEquals(95, trip(7 to 95).elapsedMin)
    }

    /** OEM: average speed = computeValue(bArr[11], bArr[10]) => p[8:9], km/h. */
    @Test
    fun `average speed is big-endian across p8 and p9`() {
        assertEquals(0x0102, trip(8 to 0x01, 9 to 0x02).avgSpeedKmh)
        assertEquals(42, trip(9 to 42).avgSpeedKmh)
    }

    @Test
    fun `trip sentinels mean no data, not 65535`() {
        val t = trip(6 to 0xFF, 7 to 0xFF, 8 to 0xFF, 9 to 0xFF)
        assertNull(t.elapsedMin)
        assertNull(t.avgSpeedKmh)
    }

    /** A zero-filled page is a fresh trip, not missing data: 0 min at 0 km/h. */
    @Test
    fun `zeros are readings`() {
        val t = trip()
        assertEquals(0, t.elapsedMin)
        assertEquals(0, t.avgSpeedKmh)
    }

    /** OEM: trip fuel = computeValue(bArr[3], bArr[2]) => p[0:1], x0.1 in the p[10] unit. */
    @Test
    fun `trip fuel is big-endian across p0 and p1 in tenths`() {
        assertEquals(25.7, trip(0 to 0x01, 1 to 0x01).tripFuel!!, 1e-9)
        assertEquals(5.4, trip(1 to 54).tripFuel!!, 1e-9)
    }

    /** OEM: "optimal" fuel = computeValue(bArr[7], bArr[6]) => p[4:5], x0.1 in the p[10] unit. */
    @Test
    fun `best fuel is big-endian across p4 and p5 in tenths`() {
        assertEquals(25.7, trip(4 to 0x01, 5 to 0x01).bestFuel!!, 1e-9)
        assertEquals(4.8, trip(5 to 48).bestFuel!!, 1e-9)
    }

    /** OEM: bArr[12] = p[10]: 1 km/L, 2 L/100km, 3 MPG(UK), anything else MPG(US). */
    @Test
    fun `fuel unit follows the vendor code table`() {
        assertEquals(CanSignal.FuelUnit.KM_PER_L, trip(10 to 1).fuelUnit)
        assertEquals(CanSignal.FuelUnit.L_PER_100KM, trip(10 to 2).fuelUnit)
        assertEquals(CanSignal.FuelUnit.MPG_UK, trip(10 to 3).fuelUnit)
        assertEquals(CanSignal.FuelUnit.MPG_US, trip(10 to 0).fuelUnit)
        assertEquals(CanSignal.FuelUnit.MPG_US, trip(10 to 7).fuelUnit)
    }

    @Test
    fun `fuel sentinels mean no data`() {
        val t = trip(0 to 0xFF, 1 to 0xFF, 4 to 0xFF, 5 to 0xFF)
        assertNull(t.tripFuel)
        assertNull(t.bestFuel)
    }
}
