package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Endpoint selection is where a USB-serial driver usually goes quietly wrong: claim the comm
 * interface instead of the data one and the link opens, reports success, and never delivers a
 * byte. Each negative control below is a device shape that must fail loudly instead.
 */
class CdcAcmTest {

    @Test
    fun `picks the data interface of a standard CDC-ACM device`() {
        val pipes = CdcAcm.findPipes(canable())

        assertEquals(CdcAcmPipes(dataInterface = 1, bulkIn = 0x81, bulkOut = 0x01, controlInterface = 0), pipes)
    }

    @Test
    fun `falls back to a vendor-class interface carrying both directions`() {
        // Some CANable builds ship vendor-class firmware with no CDC descriptors at all.
        val vendor = listOf(
            InterfaceDesc(
                0, 0xFF,
                listOf(
                    EndpointDesc(0x82, EndpointType.BULK, UsbDirection.IN),
                    EndpointDesc(0x02, EndpointType.BULK, UsbDirection.OUT),
                ),
            ),
        )

        assertEquals(CdcAcmPipes(0, 0x82, 0x02, controlInterface = null), CdcAcm.findPipes(vendor))
    }

    @Test
    fun `prefers the data interface over an earlier vendor one`() {
        val reversed = listOf(
            InterfaceDesc(
                0, 0xFF,
                listOf(
                    EndpointDesc(0x83, EndpointType.BULK, UsbDirection.IN),
                    EndpointDesc(0x03, EndpointType.BULK, UsbDirection.OUT),
                ),
            ),
        ) + canable()

        assertEquals(1, CdcAcm.findPipes(reversed)?.dataInterface)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a comm interface alone yields nothing`() {
        // Interrupt IN only. Claiming this is the classic "connected but silent" bug.
        val commOnly = canable().filter { it.usbClass == CdcAcm.CLASS_COMM }

        assertNull(CdcAcm.findPipes(commOnly))
    }

    @Test
    fun `a half-duplex data interface yields nothing`() {
        val inputOnly = listOf(
            InterfaceDesc(
                1, CdcAcm.CLASS_DATA,
                listOf(EndpointDesc(0x81, EndpointType.BULK, UsbDirection.IN)),
            ),
        )

        assertNull(CdcAcm.findPipes(inputOnly))
    }

    @Test
    fun `interrupt endpoints do not stand in for bulk ones`() {
        val interrupts = listOf(
            InterfaceDesc(
                1, CdcAcm.CLASS_DATA,
                listOf(
                    EndpointDesc(0x81, EndpointType.INTERRUPT, UsbDirection.IN),
                    EndpointDesc(0x01, EndpointType.INTERRUPT, UsbDirection.OUT),
                ),
            ),
        )

        assertNull(CdcAcm.findPipes(interrupts))
    }

    @Test
    fun `a device with no interfaces yields nothing`() {
        assertNull(CdcAcm.findPipes(emptyList()))
    }

    /** The CANable 2.0 Pro's descriptor shape: comm interface 0, data interface 1. */
    private fun canable() = listOf(
        InterfaceDesc(
            0, CdcAcm.CLASS_COMM,
            listOf(EndpointDesc(0x82, EndpointType.INTERRUPT, UsbDirection.IN)),
        ),
        InterfaceDesc(
            1, CdcAcm.CLASS_DATA,
            listOf(
                EndpointDesc(0x81, EndpointType.BULK, UsbDirection.IN),
                EndpointDesc(0x01, EndpointType.BULK, UsbDirection.OUT),
            ),
        ),
    )
}
