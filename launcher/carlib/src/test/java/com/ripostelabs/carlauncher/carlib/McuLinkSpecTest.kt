package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class McuLinkSpecTest {

    @Test
    fun blankIsTheVendorTty() {
        assertEquals(McuLinkSpec.Tty("/dev/ttyHS1", 115200), McuLinkSpec.parse(null))
        assertEquals(McuLinkSpec.VENDOR, McuLinkSpec.parse("  "))
        assertNull(McuLinkSpec.parseOptional(""))
    }

    @Test
    fun ttyWithAndWithoutBaud() {
        assertEquals(McuLinkSpec.Tty("/dev/ttyS1", 115200), McuLinkSpec.parse("tty:/dev/ttyS1"))
        assertEquals(McuLinkSpec.Tty("/dev/ttyS1", 9600), McuLinkSpec.parse("tty:/dev/ttyS1@9600"))
        assertEquals(McuLinkSpec.Tty("/dev/hvc2", TtyLink.BAUD_UNCHANGED), McuLinkSpec.parse("tty:/dev/hvc2@0"))
    }

    @Test
    fun devAndTcp() {
        assertEquals(McuLinkSpec.Dev("/dev/vport8p2"), McuLinkSpec.parse("dev:/dev/vport8p2"))
        assertEquals(McuLinkSpec.Tcp("10.0.2.2", 5590), McuLinkSpec.parse(" tcp:10.0.2.2:5590 "))
    }

    @Test
    fun malformedIsAnErrorNotAFallback() {
        assertThrows(IllegalArgumentException::class.java) { McuLinkSpec.parse("usb:/dev/x") }
        assertThrows(IllegalArgumentException::class.java) { McuLinkSpec.parse("tcp:10.0.2.2") }
        assertThrows(IllegalArgumentException::class.java) { McuLinkSpec.parse("tcp:10.0.2.2:70000") }
        assertThrows(IllegalArgumentException::class.java) { McuLinkSpec.parse("tty:/dev/ttyS1@fast") }
        assertThrows(IllegalArgumentException::class.java) { McuLinkSpec.parse("tty:") }
    }
}
