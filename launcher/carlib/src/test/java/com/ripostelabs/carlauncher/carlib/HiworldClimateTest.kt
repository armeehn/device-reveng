package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 0x31 climate layout taken from `HiworldCanParseToyota.OnHandleCanAirCmdVertical`.
 *
 * Reading the OEM's *generic* air handler instead would give a different and wrong layout: it
 * returns immediately when `mHas31ClimateData` is set, which it is on this car, so that code is
 * dead here. This suite exists partly to stop anyone "simplifying" onto it.
 */
class HiworldClimateTest {

    /** p index = OEM bArr index - 2. */
    private fun climate(vararg pairs: Pair<Int, Int>): CanSignal.Climate {
        val p = ByteArray(12)
        pairs.forEach { (i, v) -> p[i] = v.toByte() }
        return HiworldCanDecoder.decodePayload(0x31, p) as CanSignal.Climate
    }

    @Test
    fun `power and ac bits`() {
        assertTrue(climate(0 to 0x40).on)
        assertEquals(false, climate(0 to 0x00).on)
        assertTrue(climate(1 to 0x40).acOn)
        assertTrue(climate(0 to 0x20).acMax)
        assertTrue(climate(0 to 0x08).auto)
    }

    /**
     * The OEM tests the dual bit for ZERO. A reading that "looks right" would invert this and the
     * error is invisible on a single-zone car, so it is pinned explicitly.
     */
    @Test
    fun `dual is inverted relative to every other flag`() {
        assertTrue(climate(0 to 0x00).dual)
        assertEquals(false, climate(0 to 0x04).dual)
    }

    @Test
    fun `seat heaters and coolers are two-bit levels`() {
        assertEquals(3, climate(2 to 0x0C).seatHeatRight)
        assertEquals(3, climate(2 to 0x03).seatHeatLeft)
        assertEquals(2, climate(3 to 0x80).seatCoolRight)
        assertEquals(2, climate(3 to 0x20).seatCoolLeft)
        // A level in one seat must not bleed into the other.
        assertEquals(0, climate(2 to 0x03).seatHeatRight)
        assertEquals(0, climate(3 to 0x80).seatCoolLeft)
    }

    @Test
    fun `fan step is the low nibble and covers off through seven`() {
        assertEquals(0, climate(5 to 0x00).fanStep)
        assertEquals(7, climate(5 to 0x07).fanStep)
        // High nibble belongs to something else and must be masked off.
        assertEquals(3, climate(5 to 0xF3).fanStep)
        assertEquals(4, climate(9 to 0x04).rearFanStep)
    }

    @Test
    fun `temperature is half a degree per count`() {
        assertEquals(21.0, climate(6 to 42).leftTempC!!, 0.001)
        assertEquals(22.5, climate(7 to 45).rightTempC!!, 0.001)
    }

    /** 0xFE and 0xFF mean the display reads LO and HI; they are not 127 and 127.5 degrees. */
    @Test
    fun `LO and HI sentinels decode as no value, not as a temperature`() {
        assertNull(climate(6 to 0xFE).leftTempC)
        assertNull(climate(6 to 0xFF).leftTempC)
        assertNull(climate(7 to 0xFE).rightTempC)
    }

    @Test
    fun `celsius flag follows bit0 of the first byte`() {
        assertTrue(climate(0 to 0x00).tempUnitCelsius)
        assertEquals(false, climate(0 to 0x01).tempUnitCelsius)
    }

    @Test
    fun `vent direction is kept raw rather than guessed`() {
        assertEquals(13, climate(4 to 13).ventDirectionRaw)
    }
}
