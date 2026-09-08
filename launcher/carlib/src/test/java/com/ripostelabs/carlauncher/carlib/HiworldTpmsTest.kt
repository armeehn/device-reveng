package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the 0x48 TPMS layout and, in particular, which byte carries the "no reading" sentinel.
 *
 * The OEM's `addTpms(b, b2, label)` computes `i2 = b`, `i3 = b2 + i2`, and shows `i3` kPa only
 * `if (i2 != 254)`. So the sentinel is the FIRST byte of each pair. Testing both bytes throws away
 * readings the vendor displays, because 0xFE is an ordinary value for the second byte.
 *
 * Wheel pairs in payload terms: FL 2+7, FR 3+8, RL 4+9, RR 5+10, spare 6+11.
 */
class HiworldTpmsTest {

    /** A 0x48 payload with each wheel's two bytes set; indices match the OEM pairing. */
    private fun tpms(vararg pairs: Pair<Int, Int>): CanSignal.Tpms {
        val p = ByteArray(12)
        pairs.forEach { (i, v) -> p[i] = v.toByte() }
        return HiworldCanDecoder.decodePayload(0x48, p) as CanSignal.Tpms
    }

    @Test
    fun `pressure is the sum of a wheel's two bytes`() {
        assertEquals(220, tpms(2 to 200, 7 to 20).frontLeftKpa)
        assertEquals(230, tpms(3 to 200, 8 to 30).frontRightKpa)
        assertEquals(210, tpms(4 to 200, 9 to 10).rearLeftKpa)
        assertEquals(240, tpms(5 to 200, 10 to 40).rearRightKpa)
        assertEquals(250, tpms(6 to 200, 11 to 50).spareKpa)
    }

    @Test
    fun `the first byte at 0xFE means no reading`() {
        assertNull(tpms(2 to 0xFE, 7 to 20).frontLeftKpa)
        assertNull(tpms(5 to 0xFE, 10 to 0).rearRightKpa)
    }

    /**
     * The regression this fixes. 0xFE in the SECOND byte is a real value, not a sentinel:
     * (0, 254) is 254 kPa, roughly 37 psi. The old code nulled it and the driver saw a dash on a
     * perfectly healthy tyre.
     */
    @Test
    fun `0xFE in the second byte is a pressure, not a sentinel`() {
        assertEquals(254, tpms(2 to 0, 7 to 0xFE).frontLeftKpa)
        assertEquals(354, tpms(3 to 100, 8 to 0xFE).frontRightKpa)
    }

    @Test
    fun `wheels are independent`() {
        val one = tpms(2 to 0xFE, 3 to 210, 8 to 5)
        assertNull(one.frontLeftKpa)
        assertEquals(215, one.frontRightKpa)
    }

    /** Parked, every sensor asleep: the OEM sends 0xFE in every first byte. */
    @Test
    fun `all asleep reports nothing rather than zeroes`() {
        val asleep = tpms(2 to 0xFE, 3 to 0xFE, 4 to 0xFE, 5 to 0xFE, 6 to 0xFE)
        assertNull(asleep.frontLeftKpa)
        assertNull(asleep.frontRightKpa)
        assertNull(asleep.rearLeftKpa)
        assertNull(asleep.rearRightKpa)
        assertNull(asleep.spareKpa)
    }

    @Test
    fun `a zero pair reads as zero, not as absent`() {
        assertEquals(0, tpms(2 to 0, 7 to 0).frontLeftKpa)
    }
}
