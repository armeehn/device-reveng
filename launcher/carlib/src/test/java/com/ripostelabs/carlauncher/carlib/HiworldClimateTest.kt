package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 0x31 climate layout taken from `HiworldCanParseToyota.OnHandleCanAirCmdVertical`
 * (`HiworldCanParseToyota.java:213-330`) and the 0x37 right/rear supplement from
 * `OnHandleCanAirCmdVertical2` (`:177-211`).
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

    private fun rear(vararg pairs: Pair<Int, Int>): CanSignal.ClimateRear {
        val p = ByteArray(3)
        pairs.forEach { (i, v) -> p[i] = v.toByte() }
        return HiworldCanDecoder.decodePayload(0x37, p) as CanSignal.ClimateRear
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
     * The OEM assigns `bDualOn` twice: from bArr[2] bit 2 tested for ZERO (`:220`), then from
     * bArr[3] bit 2 tested for one (`:232`). The second write is what its screen shows, so the
     * first byte's bit must not leak through.
     */
    @Test
    fun `dual is byte 1 bit 2, the second of the OEM's two writes`() {
        assertTrue(climate(1 to 0x04).dual)
        assertEquals(false, climate(0 to 0x04).dual)
        assertEquals(false, climate().dual)
    }

    /** bArr[2] bits 4 and 1, bArr[3] bits 7, 5, 3 (`:218-235`). */
    @Test
    fun `rear air, central supply, rear lock, air quality and AQS flags`() {
        assertTrue(climate(0 to 0x10).rearAirOn)
        assertTrue(climate(0 to 0x02).centralAirSupply)
        assertTrue(climate(1 to 0x80).rearLock)
        assertTrue(climate(1 to 0x20).airQuality)
        assertTrue(climate(1 to 0x08).aqsRecirculate)
        assertEquals(false, climate().rearLock)
    }

    /** bArr[4] bits 7, 6, 5 are rear auto, automatic defogging and the rear defrost (`:236-239`). */
    @Test
    fun `rear defrost is bit 5 and automatic defog bit 6`() {
        assertTrue(climate(2 to 0x20).rearDefog)
        assertEquals(false, climate(2 to 0x40).rearDefog)
        assertTrue(climate(2 to 0x40).autoDefog)
        assertTrue(climate(2 to 0x80).rearAuto)
        assertTrue(climate(2 to 0x10).maxFront)
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
        assertEquals(20.0, climate(10 to 40).rearLeftTempC!!, 0.001)
    }

    /** 0xFE and 0xFF mean the display reads LO and HI; they are not 127 and 127.5 degrees. */
    @Test
    fun `LO and HI sentinels decode as no value, not as a temperature`() {
        assertNull(climate(6 to 0xFE).leftTempC)
        assertNull(climate(6 to 0xFF).leftTempC)
        assertNull(climate(7 to 0xFE).rightTempC)
        assertEquals(CanSignal.Climate.TempLimit.HI, climate(10 to 0xFF).rearLeftTempLimit)
    }

    /** bArr[13]: `(raw * 0.5) - 40` degrees C, "--" at 0xFF (`:309-323`). */
    @Test
    fun `outside temperature is offset by minus forty and 0xFF is no reading`() {
        assertEquals(-40.0, climate(11 to 0).outsideTempC!!, 0.001)
        assertEquals(12.5, climate(11 to 105).outsideTempC!!, 0.001)
        assertNull(climate(11 to 0xFF).outsideTempC)
    }

    @Test
    fun `celsius flag follows bit0 of the first byte`() {
        assertTrue(climate(0 to 0x00).tempUnitCelsius)
        assertEquals(false, climate(0 to 0x01).tempUnitCelsius)
    }

    @Test
    fun `vent directions are kept raw rather than guessed`() {
        assertEquals(13, climate(4 to 13).ventDirectionRaw)
        assertEquals(3, climate(8 to 3).rearVentDirectionRaw)
    }

    /** 0x37: bArr[2] right vent, bArr[3] rear-right setpoint, bArr[4] rear seat levels (`:180-194`). */
    @Test
    fun `0x37 carries the right vent, rear right setpoint and rear seat levels`() {
        assertEquals(6, rear(0 to 6).rightVentDirectionRaw)
        assertEquals(23.0, rear(1 to 46).rearRightTempC!!, 0.001)
        assertEquals(CanSignal.Climate.TempLimit.LO, rear(1 to 0xFE).rearRightTempLimit)
        assertEquals(2, rear(2 to 0x80).rearSeatCoolRight)
        assertEquals(1, rear(2 to 0x10).rearSeatCoolLeft)
        assertEquals(3, rear(2 to 0x0C).rearSeatHeatRight)
        assertEquals(3, rear(2 to 0x03).rearSeatHeatLeft)
        assertEquals(0, rear(2 to 0x03).rearSeatHeatRight)
    }
}
