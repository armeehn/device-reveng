package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RAV4-178: what the suite Bluetooth app may ask the launcher to do with one bonded phone. */
class BtDeviceCommandTest {

    @Test
    fun `the three verbs parse with a device address`() {
        assertEquals(
            BtDeviceCommand(BtDeviceCommand.Op.CONNECT, "AA:BB:CC:DD:EE:FF"),
            BtDeviceCommand.parse("CONNECT", "AA:BB:CC:DD:EE:FF"),
        )
        assertEquals(BtDeviceCommand.Op.DISCONNECT, BtDeviceCommand.parse("DISCONNECT", "00:11:22:33:44:55")?.op)
        assertEquals(BtDeviceCommand.Op.FORGET, BtDeviceCommand.parse("FORGET", "00:11:22:33:44:55")?.op)
    }

    @Test
    fun `an address is upper-cased as BluetoothDevice reports it`() {
        assertEquals("AA:BB:CC:DD:EE:FF", BtDeviceCommand.parse("CONNECT", "aa:bb:cc:dd:ee:ff")?.address)
    }

    @Test
    fun `unknown verbs and bad addresses do nothing`() {
        assertNull(BtDeviceCommand.parse("PAIR", "AA:BB:CC:DD:EE:FF"))
        assertNull(BtDeviceCommand.parse(null, "AA:BB:CC:DD:EE:FF"))
        assertNull(BtDeviceCommand.parse("CONNECT", null))
        assertNull(BtDeviceCommand.parse("CONNECT", "AA:BB:CC:DD:EE"))
        assertNull(BtDeviceCommand.parse("CONNECT", "AA-BB-CC-DD-EE-FF"))
        assertNull(BtDeviceCommand.parse("CONNECT", "GG:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `the intent contract stays fixed for the suite app`() {
        assertEquals("com.ripostelabs.carlauncher.action.BT_DEVICE", BtDeviceCommand.ACTION)
        assertEquals("op", BtDeviceCommand.EXTRA_OP)
        assertEquals("address", BtDeviceCommand.EXTRA_ADDRESS)
    }
}
