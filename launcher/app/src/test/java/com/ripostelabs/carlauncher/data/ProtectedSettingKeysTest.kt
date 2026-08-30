package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw SysVar browser can write any of the ~455 live vendor keys. For most of them a wrong
 * value is an annoyance; for the ones listed in [ProtectedSettingKeys] it is a unit that will not
 * boot. These pin both halves of that: the dangerous keys are refused, and an ordinary key stays
 * editable — a refuse-list that quietly grew to cover everything would kill the browser instead.
 */
class ProtectedSettingKeysTest {

    @Test
    fun wheelLearnKeysAreRefused() {
        // The v2.4.2 incident: a scalar under either of these crash-loops the vendor gateway on
        // boot, taking the top bar, SWC and HVAC with it.
        assertTrue(ProtectedSettingKeys.isProtected(SettingKeys.WHEEL_KEY_LEARN_CUSTOM))
        assertTrue(ProtectedSettingKeys.isProtected(SettingKeys.WHEEL_CUSTOM_KEY_SAVE))
    }

    @Test
    fun panelGeometryAndBaudRatesAreRefused() {
        listOf(
            SettingKeys.SCREEN_WIDTH,
            SettingKeys.SCREEN_HEIGHT,
            SettingKeys.SCREEN_DENSITY,
            SettingKeys.CAN_BAUD_RATE,
            SettingKeys.MCU_COM_BAUDRATE,
            SettingKeys.AIR_CONDITIONING_BAUD,
        ).forEach { key ->
            assertTrue("$key must be read-only", ProtectedSettingKeys.isProtected(key))
        }
    }

    @Test
    fun everyRefusedKeyExplainsItself() {
        // The screen renders this string; an empty one would leave a dead row with no reason.
        listOf(
            SettingKeys.WHEEL_KEY_LEARN_CUSTOM,
            SettingKeys.WHEEL_CUSTOM_KEY_SAVE,
            SettingKeys.SCREEN_WIDTH,
            SettingKeys.CAN_BAUD_RATE,
        ).forEach { key ->
            val reason = ProtectedSettingKeys.reasonFor(key)
            assertNotNull("$key needs a reason", reason)
            assertTrue("$key reason is blank", reason!!.isNotBlank())
        }
    }

    @Test
    fun ordinaryKeysStayWritable() {
        listOf(
            SettingKeys.BRIGHTNESS,
            SettingKeys.TEMP_UNIT,
            SettingKeys.RADAR_TONE_ENABLE,
            SettingKeys.VOL_RADIO,
            SettingKeys.SLEEP_TIME,
        ).forEach { key ->
            assertFalse("$key must stay editable", ProtectedSettingKeys.isProtected(key))
            assertNull(ProtectedSettingKeys.reasonFor(key))
        }
    }

    @Test
    fun anUncataloguedKeyStaysWritable() {
        // The browser exists to surface keys we have never seen; it must not refuse them by default.
        assertFalse(ProtectedSettingKeys.isProtected("Sys_Some_Key_We_Have_Not_Catalogued"))
    }
}
