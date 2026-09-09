package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 2019 RAV4 body-bus signals confirmed by actuation on 2026-09-07. Every frame here is
 * copied verbatim from an archived capture, so a failure means the decoder drifted, not that the
 * fixture was guessed.
 *
 * Method behind the mapping: hold one state for ~60 s, capture, then compare the fraction of frames
 * each bit is set against a run holding a different state. The driver's-door bit read 96% with that
 * door open and 6% with only the passenger door open; the passenger bit read 0% and 84%.
 */
class RawCanDecoderTest {

    private fun frame(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** A 0x4A5 frame with the door byte substituted; the other bytes are as captured. */
    private fun doorFrame(doorByte: Int) =
        frame(0x00, 0x01, 0xE0, doorByte, 0xC8, 0x00, 0x08, 0xC6)

    private fun doors(doorByte: Int) =
        RawCanDecoder.decode(RawCanDecoder.ID_DOOR_STATUS, doorFrame(doorByte)) as RawCanSignal.Doors

    @Test
    fun `self test passes over the archived captures`() {
        assertNull(RawCanDecoder.selfTest())
    }

    @Test
    fun `each door bit maps to its own opening`() {
        assertTrue(doors(0x80).driver)
        assertTrue(doors(0x40).passenger)
        assertTrue(doors(0x20).rearRight)
        assertTrue(doors(0x10).rearLeft)
        assertTrue(doors(0x08).tailgate)
        assertTrue(doors(0x04).hood)
    }

    @Test
    fun `a set bit does not leak into its neighbours`() {
        val onlyDriver = doors(0x80)
        assertTrue(onlyDriver.driver)
        assertEquals(false, onlyDriver.passenger)
        assertEquals(false, onlyDriver.rearRight)
        assertEquals(false, onlyDriver.tailgate)
    }

    /** 0x28 appeared in the rear-door capture: tailgate and rear right open together. */
    @Test
    fun `combined openings decode together`() {
        val both = doors(0x28)
        assertTrue(both.tailgate)
        assertTrue(both.rearRight)
        assertEquals(false, both.rearLeft)
        assertTrue(both.anyOpen)
    }

    @Test
    fun `all shut reports nothing open`() {
        assertEquals(false, doors(0x00).anyOpen)
    }

    /**
     * 0x620 pulses for ~0.3 s on door activity and earlier notes called it the door signal. With a
     * door held open for 60 s it stayed clear for 47 of them. It must not decode as anything.
     */
    @Test
    fun `the 0x620 door-activity pulse is not treated as door state`() {
        assertNull(RawCanDecoder.decode(0x620, frame(0x00, 0x80, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `climate reports on and off from both messages`() {
        val offA = RawCanDecoder.decode(RawCanDecoder.ID_CLIMATE_A, frame(0, 0, 0x00, 0, 0, 0, 0x25, 0x01))
        val onA = RawCanDecoder.decode(RawCanDecoder.ID_CLIMATE_A, frame(0, 0, 0x20, 0, 0, 0, 0x25, 0x01))
        assertEquals(RawCanSignal.Climate(on = false), offA)
        assertEquals(RawCanSignal.Climate(on = true), onA)

        val offB = RawCanDecoder.decode(RawCanDecoder.ID_CLIMATE_B, frame(0, 0x7E, 0, 0, 0, 0x10, 0, 0))
        val onB = RawCanDecoder.decode(RawCanDecoder.ID_CLIMATE_B, frame(0, 0x7E, 0, 0, 0, 0x18, 0, 0))
        assertEquals(RawCanSignal.Climate(on = false), offB)
        assertEquals(RawCanSignal.Climate(on = true), onB)
    }

    /** Byte 6 is 0x00 stopped and 0x52..0x5B running; level is an offset, not the displayed step. */
    @Test
    fun `fan reports running state and a relative level`() {
        val stopped = RawCanDecoder.decode(RawCanDecoder.ID_FAN, frame(0, 0, 0x01, 0xFF, 0x51, 0x26, 0x00, 0x7B))
        assertEquals(RawCanSignal.Fan(running = false, level = 0), stopped)

        val running = RawCanDecoder.decode(RawCanDecoder.ID_FAN, frame(0, 0, 0x01, 0xFF, 0x51, 0x26, 0x56, 0x7B))
        assertEquals(RawCanSignal.Fan(running = true, level = 0x56 - 0x52), running)
    }

    @Test
    fun `short frames are refused rather than read past the end`() {
        assertNull(RawCanDecoder.decode(RawCanDecoder.ID_DOOR_STATUS, frame(0x00, 0x01)))
    }

    @Test
    fun `ids we do not decode return null`() {
        // 0x0A4 is on this bus at 78 Hz and nothing here reads it.
        assertNull(RawCanDecoder.decode(0x0A4, frame(0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(RawCanDecoder.decode(0x0A6, frame(0, 0, 0, 0, 0, 0, 0, 0)))
    }
}
