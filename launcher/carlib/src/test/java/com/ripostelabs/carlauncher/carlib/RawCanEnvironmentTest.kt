package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Temperatures and light, pinned to verbatim frames. 0x380/0x3B0 frames are from the 2026-09-07
 * driveway captures (the sunny hour that moved the outside reading 23.1 → 26.3 °C) and the
 * 2026-09-09 drive; 0x3FC is the first and last frame of the drive.
 */
class RawCanEnvironmentTest {

    private fun frame(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun climate(id: Int, vararg b: Int) = RawCanDecoder.decode(id, frame(*b)) as RawCanSignal.Climate

    /** Baseline capture: byte 6 = 0x25 → 23.125 °C; fan sweep an hour later: 0x29 → 25.625 °C. */
    @Test
    fun `outside temperature rides 0x380 at 0,625 per lsb`() {
        val early = climate(RawCanDecoder.ID_CLIMATE_A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x25, 0x01)
        assertEquals(23.125, early.outsideC!!, 0.001)
        assertNull(early.cabinC)

        val later = climate(RawCanDecoder.ID_CLIMATE_A, 0x00, 0x00, 0xA0, 0x08, 0x00, 0x00, 0x29, 0x01)
        assertEquals(25.625, later.outsideC!!, 0.001)
        assertEquals(true, later.on)
    }

    /** Drive: byte 1 = 0x6C → 20.5 °C cabin with A/C off; ac capture: 0x7C → 24.5 °C with it on. */
    @Test
    fun `cabin temperature rides 0x3B0 at a quarter degree with -6,5 offset`() {
        val cool = climate(RawCanDecoder.ID_CLIMATE_B, 0x00, 0x6C, 0x00, 0x45, 0x00, 0x10, 0x00, 0x00)
        assertEquals(20.5, cool.cabinC!!, 0.001)
        assertNull(cool.outsideC)
        assertEquals(false, cool.on)

        val warm = climate(RawCanDecoder.ID_CLIMATE_B, 0x00, 0x7C, 0x00, 0x43, 0x00, 0x18, 0x00, 0x00)
        assertEquals(24.5, warm.cabinC!!, 0.001)
        assertEquals(true, warm.on)
    }

    /** 0x67 0xF0 → (0x67 << 5) | (0xF0 >> 3) = 3326; 0x5D 0xD8 → 3003. */
    @Test
    fun `ambient light is 13 bits across bytes 6 and 7`() {
        assertEquals(3326, (RawCanDecoder.decode(RawCanDecoder.ID_AMBIENT_LIGHT, frame(0x00, 0x00, 0x0B, 0x00, 0x0E, 0x80, 0x67, 0xF0)) as RawCanSignal.AmbientLight).level)
        assertEquals(3003, (RawCanDecoder.decode(RawCanDecoder.ID_AMBIENT_LIGHT, frame(0x00, 0x00, 0x01, 0x80, 0x06, 0x80, 0x5D, 0xD8)) as RawCanSignal.AmbientLight).level)
    }

    /** The two climate ids each carry one temperature; folding both must keep both. */
    @Test
    fun `both temperatures survive folding the other climate id`() {
        val state = VehicleState()
        val t = 9_000L
        state.onRawFrame(RawCanDecoder.ID_CLIMATE_A, frame(0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x24, 0x01), t)
        state.onRawFrame(RawCanDecoder.ID_CLIMATE_B, frame(0x00, 0x6C, 0x00, 0x45, 0x00, 0x10, 0x00, 0x00), t)
        val tiles = state.tiles(now = t).associate { it.label to it.value }

        assertEquals("22.5°C", tiles["Outside"])
        assertEquals("20.5°C", tiles["Cabin"])
    }
}
