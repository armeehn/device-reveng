package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 0x18 side-camera bits.
 *
 * The opcode is named LightInfo by the OEM and its handler touches no lamps — it reads three bits
 * of p[1] and opens the side cameras. Anyone skimming the name would expect headlight state here
 * and find none, so the shape is pinned.
 *
 * This is also the only indicator state the head unit exposes: three actuation hunts on the raw CAN
 * bus (2026-09-07, including with the car in READY) found no indicator signal at all.
 */
class HiworldSideCameraTest {

    private fun cam(b1: Int): CanSignal.SideCamera {
        val p = ByteArray(4)
        p[1] = b1.toByte()
        return HiworldCanDecoder.decodePayload(0x18, p) as CanSignal.SideCamera
    }

    @Test
    fun `each side has its own bit`() {
        assertTrue(cam(0x80).right)
        assertEquals(false, cam(0x80).left)
        assertTrue(cam(0x40).left)
        assertEquals(false, cam(0x40).right)
    }

    @Test
    fun `nothing requested when no bits are set`() {
        val none = cam(0x00)
        assertEquals(false, none.left)
        assertEquals(false, none.right)
        assertEquals(false, none.leftForced)
    }

    /** Bit3 forces the left view on independently of bit6; [left] must reflect that. */
    @Test
    fun `bit3 forces the left camera on its own`() {
        val forced = cam(0x08)
        assertTrue(forced.left)
        assertTrue(forced.leftForced)
        assertEquals(false, forced.right)
    }

    @Test
    fun `forced and normal left are distinguishable`() {
        assertEquals(false, cam(0x40).leftForced)
        assertTrue(cam(0x48).leftForced)
        assertTrue(cam(0x48).left)
    }

    @Test
    fun `both sides can be requested at once`() {
        val both = cam(0xC0)
        assertTrue(both.left)
        assertTrue(both.right)
    }
}
