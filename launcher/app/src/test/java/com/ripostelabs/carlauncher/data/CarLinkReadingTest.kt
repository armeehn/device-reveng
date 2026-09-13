package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.McuOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLinkReadingTest {

    @Test
    fun `no owner reads the vendor gateway`() {
        val up = CarLink.read(null, gatewayConnected = true)
        assertTrue(up.ok)
        assertEquals(CarLink.VENDOR_TITLE, up.title)
        assertEquals(CarLink.VENDOR_CONNECTED, up.detail)

        val down = CarLink.read(null, gatewayConnected = false)
        assertFalse(down.ok)
        assertEquals(CarLink.VENDOR_DISCONNECTED, down.detail)
    }

    @Test
    fun `blocked and failed carry the reason`() {
        val blocked = CarLink.read(McuOwner.Status.Blocked("eventcenter owns the port"), gatewayConnected = true)
        assertFalse(blocked.ok)
        assertEquals(CarLink.BLOCKED_TITLE, blocked.title)
        assertEquals("eventcenter owns the port", blocked.detail)

        val failed = CarLink.read(McuOwner.Status.Failed("link closed"), gatewayConnected = true)
        assertFalse(failed.ok)
        assertEquals(CarLink.FAILED_TITLE, failed.title)
        assertEquals("link closed", failed.detail)
    }

    @Test
    fun `idle is not ok`() {
        val idle = CarLink.read(McuOwner.Status.Idle, gatewayConnected = true)
        assertFalse(idle.ok)
        assertEquals(CarLink.IDLE_TITLE, idle.title)
    }

    @Test
    fun `no frames means wrong port or baud`() {
        val status = McuOwner.Status.Running(acked = false, frames = 0, badChecksum = 0, skipped = 0)
        assertEquals(CarLink.NO_BYTES, CarLink.running(status))
        assertFalse(CarLink.read(status, gatewayConnected = false).ok)
    }

    @Test
    fun `frames without ack means wrong ack formula`() {
        val status = McuOwner.Status.Running(acked = false, frames = 12, badChecksum = 1, skipped = 0)
        assertEquals(CarLink.NOT_ACKED, CarLink.running(status))
        assertFalse(CarLink.read(status, gatewayConnected = false).ok)
    }

    @Test
    fun `acked is ok and lists every counter`() {
        val status = McuOwner.Status.Running(acked = true, frames = 40, badChecksum = 2, skipped = 3)
        val reading = CarLink.read(status, gatewayConnected = false)
        assertTrue(reading.ok)
        assertEquals(CarLink.RUNNING_TITLE, reading.title)
        assertEquals("MCU acknowledged (acked=true frames=40 badChecksum=2 skipped=3)", reading.detail)
    }

    @Test
    fun `an acked session with zero frames still reads no bytes`() {
        // frames counts received frames; acked with none is a contradiction worth surfacing.
        val status = McuOwner.Status.Running(acked = true, frames = 0, badChecksum = 0, skipped = 0)
        assertEquals(CarLink.NO_BYTES, CarLink.running(status))
    }
}
