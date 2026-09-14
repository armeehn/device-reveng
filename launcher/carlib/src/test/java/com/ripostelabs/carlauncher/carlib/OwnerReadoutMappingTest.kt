package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner path's two pure mappings: a decoded 0x31 frame → [ClimateState] (the Home climate
 * card) and a decoded 0x11 frame → [DoorState]. Both are built from bytes through
 * [HiworldCanDecoder.decodePayload], so the sentinel plumbing is covered end to end, and the
 * expected labels are what canbus2's `setCanAirTempInfoVertical` would have put in the parcel.
 */
class OwnerReadoutMappingTest {

    private companion object {
        const val OP_CLIMATE = 0x31
        const val OP_BASIC_STATUS = 0x11
        const val TEMP_LO = 0xFE
        const val TEMP_HI = 0xFF
        const val AT_MS = 1_000L
    }

    /** 0x31 payload: p[0] flags, p[1] flags, p[4] vent, p[5] fan, p[6]/p[7] setpoints × 2. */
    private fun climate(
        b0: Int = 0x40,
        b1: Int = 0x40,
        vent: Int = 0,
        fan: Int = 0,
        left: Int = 0,
        right: Int = left,
    ): CanSignal.Climate {
        val p = ByteArray(10)
        p[0] = b0.toByte()
        p[1] = b1.toByte()
        p[4] = vent.toByte()
        p[5] = fan.toByte()
        p[6] = left.toByte()
        p[7] = right.toByte()
        return HiworldCanDecoder.decodePayload(OP_CLIMATE, p) as CanSignal.Climate
    }

    @Test
    fun `setpoints read like the vendor strings`() {
        val state = ClimateState.from(climate(fan = 3, left = 42, right = 45))

        assertTrue(state.valid)
        assertTrue(state.powerOn)
        assertEquals("21.0℃", state.leftTemp)
        assertEquals("22.5℃", state.rightTemp)
        assertEquals("21.0℃", state.leftTempLabel())
        assertEquals(3, state.fanLevel)
        assertEquals(ClimateState.TempUnit.CELSIUS, state.tempUnit)
    }

    @Test
    fun `dial end stops are LO and HI, not blanks`() {
        val signal = climate(left = TEMP_LO, right = TEMP_HI)
        assertNull(signal.leftTempC)
        assertEquals(CanSignal.Climate.TempLimit.LO, signal.leftTempLimit)

        val state = ClimateState.from(signal)
        assertEquals("LO", state.leftTemp)
        assertEquals("HI", state.rightTemp)
    }

    @Test
    fun `power off blanks the temperatures and the card says Off`() {
        val state = ClimateState.from(climate(b0 = 0x00, left = 42))

        assertFalse(state.powerOn)
        assertEquals("", state.leftTemp)
        assertEquals("Off", state.leftTempLabel())
    }

    @Test
    fun `fahrenheit is the raw byte halved, whole degrees`() {
        // b0 bit 0 = ℉; raw 141 → 70℉ (integer division, as the vendor does it).
        val state = ClimateState.from(climate(b0 = 0x41, left = 141))

        assertEquals(ClimateState.TempUnit.FAHRENHEIT, state.tempUnit)
        assertEquals("70℉", state.leftTemp)
    }

    @Test
    fun `flags and vent direction follow the vendor handler`() {
        // b1: A/C on (0x40) + recirculate (0x10); vent 13 = head + level.
        val state = ClimateState.from(climate(b0 = 0x40 or 0x08, b1 = 0x50, vent = 13))

        assertTrue(state.acOn)
        assertTrue(state.autoOn)
        assertFalse(state.outsideAir)
        assertTrue(state.modeHead)
        assertTrue(state.modeLevel)
        assertFalse(state.modeFoot)

        val foot = ClimateState.from(climate(vent = 3))
        assertFalse(foot.modeHead)
        assertFalse(foot.modeLevel)
        assertTrue(foot.modeFoot)
    }

    /** 0x11 payload with only the door byte (p[4]) set. */
    private fun doors(doorByte: Int): DoorState {
        val p = ByteArray(8)
        p[4] = doorByte.toByte()
        return DoorState.from(HiworldCanDecoder.decodePayload(OP_BASIC_STATUS, p) as CanSignal.BasicStatus, AT_MS)
    }

    @Test
    fun `doors are copied by name, so the 0x11 driver bit lands on frontLeft`() {
        // 0x40 is the driver on this opcode; the broadcast byte had it at 0x80 (DoorState.fromByte).
        val driver = doors(0x40)
        assertTrue(driver.frontLeft)
        assertFalse(driver.frontRight)
        assertEquals(AT_MS, driver.atMs)
        assertEquals(DoorState.fromByte(0x80, AT_MS), driver)

        val rest = doors(0x80 or 0x20 or 0x10 or 0x08 or 0x04)
        assertFalse(rest.frontLeft)
        assertTrue(rest.frontRight && rest.rearRight && rest.rearLeft && rest.tailgate && rest.bonnet)
        assertFalse(doors(0).anyOpen())
    }
}
