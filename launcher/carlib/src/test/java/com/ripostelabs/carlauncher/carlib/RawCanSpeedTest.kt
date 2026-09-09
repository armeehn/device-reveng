package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the raw-bus speed decode to frames copied verbatim from the 2026-09-09 drive, each paired
 * with the ECU's own OBD PID 0x0D answer from the same quarter second. The reference is the car,
 * not a plausible-looking correlation: 1,135 replies, 0..60 km/h, three ECUs agreeing on every one.
 */
class RawCanSpeedTest {

    private fun frame(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun speed(id: Int, vararg b: Int) = RawCanDecoder.decode(id, frame(*b)) as RawCanSignal.Speed

    /** ECU said 42 km/h; 0x361 byte 6 read 0x2A. */
    @Test
    fun `fast speed id reads integer kmh from byte 6`() {
        assertEquals(42.0, speed(RawCanDecoder.ID_SPEED_FAST, 0x80, 0x27, 0x56, 0x00, 0x56, 0x00, 0x2A, 0x7B).kmh, 0.0)
        assertEquals(0.0, speed(RawCanDecoder.ID_SPEED_FAST, 0x80, 0x00, 0x00, 0x00, 0xD6, 0x00, 0x00, 0x8C).kmh, 0.0)
    }

    /** ECU said 42 km/h; 0x498 byte 5 read 0x2A. */
    @Test
    fun `slow speed id reads integer kmh from byte 5`() {
        assertEquals(42.0, speed(RawCanDecoder.ID_SPEED_SLOW, 0x50, 0x07, 0x41, 0xB6, 0x45, 0x2A, 0x00, 0xC8).kmh, 0.0)
        assertEquals(0.0, speed(RawCanDecoder.ID_SPEED_SLOW, 0x50, 0x00, 0x00, 0xB7, 0x42, 0x00, 0x00, 0x1E).kmh, 0.0)
    }

    /** ECU said 42 (truncated); the stability ECU's 0.01 km/h field read 0x10D5 = 43.09. */
    @Test
    fun `vsc speed is a signed hundredth of a kmh`() {
        assertEquals(43.09, speed(RawCanDecoder.ID_SPEED_VSC, 0x00, 0x00, 0x00, 0x00, 0x0D, 0x10, 0xD5, 0xAE).kmh, 0.001)
        assertEquals(0.0, speed(RawCanDecoder.ID_SPEED_VSC, 0x00, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x00, 0xF8).kmh, 0.0)
    }

    /** Four wheels at 42 km/h: 0x2B39 → 42.98, 0x2B34 → 42.93, 0x2B28 → 42.81, 0x2B12 → 42.59. */
    @Test
    fun `wheel speeds carry the opendbc offset and land in wheel order`() {
        val w = RawCanDecoder.decode(RawCanDecoder.ID_WHEEL_SPEEDS,
            frame(0x2B, 0x39, 0x2B, 0x34, 0x2B, 0x28, 0x2B, 0x12)) as RawCanSignal.WheelSpeeds
        assertEquals(42.98, w.frKmh, 0.001)
        assertEquals(42.93, w.flKmh, 0.001)
        assertEquals(42.81, w.rrKmh, 0.001)
        assertEquals(42.59, w.rlKmh, 0.001)
    }

    /** Parked: every wheel reads the raw offset, 0x1A6F, which must come out as exactly zero. */
    @Test
    fun `stationary wheels read zero not the raw offset`() {
        val w = RawCanDecoder.decode(RawCanDecoder.ID_WHEEL_SPEEDS,
            frame(0x1A, 0x6F, 0x1A, 0x6F, 0x1A, 0x6F, 0x1A, 0x6F)) as RawCanSignal.WheelSpeeds
        listOf(w.flKmh, w.frKmh, w.rlKmh, w.rrKmh).forEach { assertEquals(0.0, it, 0.001) }
    }

    /** End to end: a raw frame lands on the dashboard as a rounded speed tile. */
    @Test
    fun `speed reaches the vehicle tiles`() {
        val state = VehicleState()
        state.onRawFrame(RawCanDecoder.ID_SPEED_VSC, frame(0x00, 0x00, 0x00, 0x00, 0x0D, 0x10, 0xD5, 0xAE), atMs = 1_000L)
        val tile = state.tiles(now = 1_000L).first { it.label == "Speed" }
        assertEquals("43 km/h", tile.value)
        assertTrue(state.snapshot.value.speedKmh!! > 43.0)
    }
}
