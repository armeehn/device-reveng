package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the inbound btsuite contract (`BTService.java:1496-1530`, `:1338-1341`, `:1801-1836`). */
class VendorBtControlTest {

    @Test
    fun dialCarriesKeyFiveAndTheNumber() {
        assertEquals(
            IntentSpec(
                action = "zxw_bluetooth_contral_action",
                ints = mapOf("zxw_bluetooth_contral_key" to 5),
                strings = mapOf("zxw_bluetooth_contral_key_value_str" to "+16041234567"),
            ),
            VendorBt.dial("+16041234567"),
        )
    }

    @Test
    fun connectAndDisconnect() {
        assertEquals(10, VendorBt.connect("AA:BB:CC:DD:EE:FF").ints["zxw_bluetooth_contral_key"])
        assertEquals("AA:BB:CC:DD:EE:FF", VendorBt.connect("AA:BB:CC:DD:EE:FF").strings["zxw_bluetooth_contral_key_value_str"])
        assertEquals(11, VendorBt.disconnect().ints["zxw_bluetooth_contral_key"])
        assertEquals(emptyMap<String, String>(), VendorBt.disconnect().strings)
    }

    @Test
    fun hangUpIsItsOwnAction() {
        assertEquals(IntentSpec("com.szchoiceway.btsuite.HBCP_HANGUP_EVENT"), VendorBt.hangUp())
    }

    @Test
    fun answerRidesMcuKeyInfor() {
        assertEquals(
            IntentSpec(
                action = CarEvents.MCU_KEY_INFOR_ACTION,
                ints = mapOf(CarEvents.EXTRA_MCU_KEY_VALUE to 23),
            ),
            VendorBt.answer(),
        )
        // Our own fallback receiver must not turn that code into a wheel key.
        assertEquals(null, SwcFallback.mcuKey(VendorBt.MCU_KEY_ANSWER))
        assertEquals(null, SwcFallback.mcuKey(VendorBt.MCU_KEY_HANG_UP))
    }
}
