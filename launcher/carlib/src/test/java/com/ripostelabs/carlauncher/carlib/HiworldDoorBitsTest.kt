package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 0x11 door bitfield, and specifically guards the bit that was wrong.
 *
 * `doorFrontLeftOpen` previously read bit6 (0x40), which is the FRONT RIGHT door — so the launcher
 * showed the passenger door as the driver's. Two independent sources fix it at bit7 (0x80): the
 * vendor's own `DoorInfoWindow.setDoorData` maps `i and 128` to the front-left image, and a
 * 2026-09-07 raw-bus actuation capture (0x4A5 byte 3, identical layout) had 0x80 set for 96% of a
 * driver's-door-open run against 6% of a passenger-only run.
 */
class HiworldDoorBitsTest {

    /** A 0x11 payload with only the door byte set; p[4] is the OEM's bArr[6]. */
    private fun status(doorByte: Int): CanSignal.BasicStatus {
        val p = ByteArray(8)
        p[4] = doorByte.toByte()
        return HiworldCanDecoder.decodePayload(0x11, p) as CanSignal.BasicStatus
    }

    @Test
    fun `driver door is bit7, not bit6`() {
        assertTrue(status(0x80).doorFrontLeftOpen)
        // The regression: 0x40 alone must NOT read as the driver's door.
        assertEquals(false, status(0x40).doorFrontLeftOpen)
        assertTrue(status(0x40).doorFrontRightOpen)
    }

    @Test
    fun `every opening maps to its own bit`() {
        assertTrue(status(0x80).doorFrontLeftOpen)
        assertTrue(status(0x40).doorFrontRightOpen)
        assertTrue(status(0x20).doorRearRightOpen)
        assertTrue(status(0x10).doorRearLeftOpen)
        assertTrue(status(0x08).tailgateOpen)
        assertTrue(status(0x04).hoodOpen)
    }

    @Test
    fun `bits do not leak into each other`() {
        val s = status(0x80)
        assertEquals(false, s.doorFrontRightOpen)
        assertEquals(false, s.doorRearLeftOpen)
        assertEquals(false, s.tailgateOpen)
        assertEquals(false, s.hoodOpen)
    }

    /** 0x28 was observed on the raw bus: tailgate and rear right open together. */
    @Test
    fun `combined openings decode together`() {
        val s = status(0x28)
        assertTrue(s.tailgateOpen)
        assertTrue(s.doorRearRightOpen)
        assertEquals(false, s.doorRearLeftOpen)
        assertEquals(false, s.doorFrontLeftOpen)
    }

    @Test
    fun `all shut reports nothing open`() {
        val s = status(0x00)
        assertEquals(false, s.doorFrontLeftOpen)
        assertEquals(false, s.doorFrontRightOpen)
        assertEquals(false, s.tailgateOpen)
        assertEquals(0, s.doorBits)
    }
}
