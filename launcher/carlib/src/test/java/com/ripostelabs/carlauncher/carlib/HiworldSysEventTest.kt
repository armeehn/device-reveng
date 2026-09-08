package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 0x71 system event, whose only currently-useful field is the reverse flag.
 *
 * Provenance is the vendor gateway, not the car parser: `EventService.processCmd` takes the opcode
 * from `bArr[0]` and passes the whole frame on, so its `bArr[1]` is this decoder's `payload[0]`.
 * `onCmdSysEvent` then computes reverse as `(bArr[1] & 2) > 0 && mAccOpenState`.
 *
 * NOT actuation-verified — the reverse camera was physically disconnected the day this was written.
 */
class HiworldSysEventTest {

    private fun sys(b0: Int, b1: Int = 0): CanSignal.SysEvent {
        val p = byteArrayOf(b0.toByte(), b1.toByte(), 0)
        return HiworldCanDecoder.decodePayload(0x71, p) as CanSignal.SysEvent
    }

    @Test
    fun `reverse is bit1 of the first payload byte`() {
        assertTrue(sys(0x02).reverseRaw)
        assertEquals(false, sys(0x00).reverseRaw)
    }

    /**
     * The vendor ANDs the bit with ACC before believing it, and this decoder cannot see ACC. The
     * raw bit must therefore stay raw — silently gating it here would make the field lie about
     * what the car said.
     */
    @Test
    fun `the ACC gate is deliberately not applied here`() {
        val onlyReverse = sys(0x02)
        assertTrue(onlyReverse.reverseRaw)
        assertEquals(0x02, onlyReverse.raw)
    }

    @Test
    fun `disc and usb are the other two named bits`() {
        assertTrue(sys(0x80).discPresent)
        assertTrue(sys(0x40).usbPresent)
        assertEquals(false, sys(0x80).usbPresent)
        assertEquals(false, sys(0x40).discPresent)
    }

    @Test
    fun `named bits do not bleed into reverse`() {
        val discAndUsb = sys(0xC0)
        assertTrue(discAndUsb.discPresent)
        assertTrue(discAndUsb.usbPresent)
        assertEquals(false, discAndUsb.reverseRaw)
    }

    @Test
    fun `several flags decode together`() {
        val all = sys(0xC2)
        assertTrue(all.discPresent)
        assertTrue(all.usbPresent)
        assertTrue(all.reverseRaw)
    }

    /** Unnamed bits are preserved rather than given invented meanings. */
    @Test
    fun `unnamed bits survive in raw`() {
        assertEquals(0x1D, sys(0x1D).raw)
        assertEquals(false, sys(0x1D).reverseRaw)
    }
}
