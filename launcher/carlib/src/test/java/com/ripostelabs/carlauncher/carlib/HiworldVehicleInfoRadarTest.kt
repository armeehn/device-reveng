package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins 0x32 (vehicle info) and 0x41 (parking radar) at the decoder level. Both were audited against
 * the vendor parser and found correct; neither had a test, and a correct decode with no test is one
 * refactor away from silently flipping — which is exactly how the door bits and next/prev went
 * wrong earlier in this project.
 *
 * The byte ORDER is the fragile part and is pinned deliberately. The OEM reads speed as
 * `computeValue(bArr[7], bArr[6])`, and `computeValue(low, high) = (high shl 8) or low`, so the
 * high byte is `bArr[6]` — big-endian across p[4]/p[5]. Getting that backwards yields plausible
 * numbers, which is what makes it dangerous.
 */
class HiworldVehicleInfoRadarTest {

    private fun payload(vararg pairs: Pair<Int, Int>): ByteArray {
        val p = ByteArray(12)
        pairs.forEach { (i, v) -> p[i] = v.toByte() }
        return p
    }

    private fun info(vararg pairs: Pair<Int, Int>) =
        HiworldCanDecoder.decodePayload(0x32, payload(*pairs)) as CanSignal.VehicleInfo

    private fun radar(vararg pairs: Pair<Int, Int>) =
        HiworldCanDecoder.decodePayload(0x41, payload(*pairs)) as CanSignal.ParkingRadar

    // ---- 0x32 vehicle info ------------------------------------------------------------------

    /** OEM: byEngineSpeedH = bArr[4] = p[2], byEngineSpeedL = bArr[5] = p[3]. */
    @Test
    fun `rpm is big-endian across p2 and p3`() {
        assertEquals(0x0BB8, info(2 to 0x0B, 3 to 0xB8).rpm)
        assertEquals(0, info().rpm)
        // If the order were flipped this would read 0xB80B, so the asymmetric value matters.
        assertEquals(3000, info(2 to 0x0B, 3 to 0xB8).rpm)
    }

    @Test
    fun `raw speed is big-endian across p4 and p5`() {
        assertEquals(0x0100, info(4 to 0x01, 5 to 0x00).speedRaw)
        assertEquals(1, info(5 to 0x01).speedRaw)
        assertEquals(256, info(4 to 0x01).speedRaw)
    }

    /** OEM: temperature is bArr[11] = p[9], displayed as i - 40, with 255 meaning unsupported. */
    @Test
    fun `coolant subtracts the forty degree offset`() {
        assertEquals(0, info(9 to 40).coolantC)
        assertEquals(50, info(9 to 90).coolantC)
        assertEquals(-40, info(9 to 0).coolantC)
    }

    @Test
    fun `coolant sentinel means no reading, not 215 degrees`() {
        assertNull(info(9 to 0xFF).coolantC)
    }

    // ---- 0x41 parking radar -----------------------------------------------------------------

    /** OEM: rear is bArr[2..5] = p[0..3], front is bArr[6..9] = p[4..7]. */
    @Test
    fun `rear sensors come before front in the payload`() {
        val r = radar(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 4, 6 to 3, 7 to 2)
        assertEquals(listOf(30, 60, 90, 120), r.rearCm)
        assertEquals(listOf(150, 120, 90, 60), r.frontCm)
    }

    /** OEM: a step of 1..5 becomes value * 30; anything else is "no object". */
    @Test
    fun `each step is thirty centimetres`() {
        assertEquals(30, radar(0 to 1).rearCm[0])
        assertEquals(150, radar(0 to 5).rearCm[0])
    }

    @Test
    fun `zero and out-of-range steps report no object rather than a distance`() {
        assertNull(radar(0 to 0).rearCm[0])
        assertNull(radar(0 to 6).rearCm[0])
        assertNull(radar(0 to 0xFF).rearCm[0])
    }

    /** Parked with nothing near: every sensor reads zero and nothing should claim a distance. */
    @Test
    fun `an all-zero frame reports nothing anywhere`() {
        val r = radar()
        assertEquals(List(4) { null }, r.rearCm)
        assertEquals(List(4) { null }, r.frontCm)
    }
}
