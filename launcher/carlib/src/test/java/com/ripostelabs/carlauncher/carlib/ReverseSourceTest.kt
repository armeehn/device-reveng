package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the reverse line comes from: the MCU's reverse wire alone (stock default), or the wire
 * OR the CAN box's gear. The wire always wins: nothing the CAN side says can take the camera
 * down while the wire says reverse.
 */
class ReverseSourceTest {

    /** A 0x1A payload in the car's 12-byte shape; p[1]/p[5] as the 2026-08-29 drive saw them. */
    private fun gearFrame(coarseB1: Int, gearB5: Int): Gear {
        val p = ByteArray(12)
        p[1] = coarseB1.toByte()
        p[5] = gearB5.toByte()
        return (HiworldCanDecoder.decodePayload(0x1A, p) as CanSignal.RpmGearMirror).gear
    }

    /** Stock `Sys_Mcu_soft_back_car_Set` defaults to 0 (EventService.java:6585): wire only. */
    @Test
    fun `default is the wire, as on stock`() {
        assertEquals(ReverseSource.WIRE, ReverseSource.DEFAULT)
        assertEquals(ReverseSource.WIRE, ReverseSource.of(0))
        assertEquals(ReverseSource.WIRE, ReverseSource.of(7))
        assertEquals(ReverseSource.WIRE_OR_CAN, ReverseSource.of(1))
    }

    @Test
    fun `wire only ignores the CAN gear`() {
        assertFalse(ReverseSource.WIRE.line(wire = false, canGear = gearFrame(0x03, 0x03)))
        assertTrue(ReverseSource.WIRE.line(wire = true, canGear = gearFrame(0x01, 0x00)))
    }

    @Test
    fun `CAN reverse raises the line when the wire is silent`() {
        val reverse = gearFrame(0x03, 0x03)
        assertEquals(Gear.REVERSE, reverse)
        assertTrue(ReverseSource.WIRE_OR_CAN.line(wire = false, canGear = reverse))
    }

    /** Safety: the wire says reverse, so the picture stays whatever the CAN gear claims. */
    @Test
    fun `the wire always wins`() {
        listOf(gearFrame(0x01, 0x01), gearFrame(0x01, 0x00), gearFrame(0x01, 0x02), gearFrame(0x01, 0xFF), null)
            .forEach { gear ->
                assertTrue("gear $gear", ReverseSource.WIRE_OR_CAN.line(wire = true, canGear = gear))
            }
    }

    @Test
    fun `other gears and a stale gear do not raise the line`() {
        assertFalse(ReverseSource.WIRE_OR_CAN.line(wire = false, canGear = gearFrame(0x01, 0x00)))
        assertFalse(ReverseSource.WIRE_OR_CAN.line(wire = false, canGear = gearFrame(0x01, 0x01)))
        assertFalse(ReverseSource.WIRE_OR_CAN.line(wire = false, canGear = gearFrame(0x01, 0xFF)))
        assertFalse(ReverseSource.WIRE_OR_CAN.line(wire = false, canGear = null))
    }

    /** A gear older than the stale window counts as unknown, so a box gone quiet in R lets go. */
    @Test
    fun `CAN gear expires`() {
        val watch = CanGearWatch()
        watch.onGear(Gear.REVERSE, atMs = 1_000)

        assertEquals(Gear.REVERSE, watch.gear(nowMs = 1_000 + CanGearWatch.STALE_MS - 1))
        assertEquals(null, watch.gear(nowMs = 1_000 + CanGearWatch.STALE_MS))
    }
}
