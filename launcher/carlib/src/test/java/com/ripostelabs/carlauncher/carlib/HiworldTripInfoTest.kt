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
}
