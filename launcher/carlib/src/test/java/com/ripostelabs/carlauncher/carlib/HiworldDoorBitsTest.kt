package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 0x11 door bitfield, with the front pair as the CAR reports it.
 *
 * This flipped twice, so the reasoning is written down. `DoorInfoWindow` draws the front-left door
 * from bit 0x80 — but of a byte the vendor has already REPACKED. `OnHandleCanDoorInfoCmd` hands
 * incoming bit7 to `sendDoorInfo` as its first argument, and `sendDoorInfo` writes its *second*
 * argument to bit7, exchanging 6<->7 (and 4<->5) on the way through. So the driver's door arrives
 * on this opcode as **0x40**, not 0x80.
 *
 * Confirmed in the car on 2026-09-07: with the decoder reading 0x80, opening the driver's door was
 * reported as the passenger's.
 *
 * The raw CAN message is a genuinely different layout — 0x4A5 byte 3 really does use 0x80 for the
 * driver, proven by actuation. Assuming the two matched is what caused the inversion.
 */
class HiworldDoorBitsTest {

    /** A 0x11 payload with only the door byte set; p[4] is the OEM's bArr[6]. */
    private fun status(doorByte: Int): CanSignal.BasicStatus {
        val p = ByteArray(8)
        p[4] = doorByte.toByte()
        return HiworldCanDecoder.decodePayload(0x11, p) as CanSignal.BasicStatus
    }

    @Test
    fun `driver door is bit6, because the vendor swaps 6 and 7 on the way in`() {
        assertTrue(status(0x40).doorFrontLeftOpen)
        // The regression, in both directions: 0x80 is the PASSENGER on this opcode.
        assertEquals(false, status(0x80).doorFrontLeftOpen)
        assertTrue(status(0x80).doorFrontRightOpen)
    }

    @Test
    fun `every opening maps to its own bit`() {
        assertTrue(status(0x40).doorFrontLeftOpen)
        assertTrue(status(0x80).doorFrontRightOpen)
        assertTrue(status(0x20).doorRearRightOpen)
        assertTrue(status(0x10).doorRearLeftOpen)
        assertTrue(status(0x08).tailgateOpen)
        assertTrue(status(0x04).hoodOpen)
    }

    @Test
    fun `bits do not leak into each other`() {
        val s = status(0x40)
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
        assertEquals(false, s.doorFrontRightOpen)
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
