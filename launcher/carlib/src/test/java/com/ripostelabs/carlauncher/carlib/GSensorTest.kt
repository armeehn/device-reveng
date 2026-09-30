package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MCU 0x8E is the G-sensor the stock gyro page reads (`GyroScopeWithCompassView.getGyroData`),
 * not a radar. The bytes below are real: `unhandled opcode 0x8E (6 bytes): 60 C2 30 FF 20 06`
 * from the car's diag log of 2026-09-20.
 */
class GSensorTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `opcode 0x8E is the G-sensor`() {
        assertEquals(McuOpcode.G_SENSOR, McuOpcode.of(0x8E))
    }

    @Test
    fun `axes are signed little-endian pairs`() {
        val g = GSensor.decode(bytes(0x60, 0xC2, 0x30, 0xFF, 0x20, 0x06))!!

        assertEquals(-15776, g.x)
        assertEquals(-208, g.y)
        assertEquals(1568, g.z)
    }

    /** Stock: `acos(axis / |v|)` in degrees per axis. Gravity sits mostly on -x on this unit. */
    @Test
    fun `axis angles follow the stock formula`() {
        val g = GSensor.decode(bytes(0x60, 0xC2, 0x30, 0xFF, 0x20, 0x06))!!

        assertEquals(174.3, g.xDeg, 0.1)
        assertEquals(90.8, g.yDeg, 0.1)
        assertEquals(84.3, g.zDeg, 0.1)
    }

    @Test
    fun `short or zero payload is no reading`() {
        assertNull(GSensor.decode(bytes(0x60, 0xC2, 0x30)))
        assertNull(GSensor.decode(bytes(0, 0, 0, 0, 0, 0)))
    }
}
