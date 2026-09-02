package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * [SettingKeys] is a hand transcription of vendor keynames out of a decompiled `SysProviderOpt`,
 * grown one settings screen at a time. Nothing in the type system stops two constants carrying the
 * same string, and the failure that causes is quiet and confusing: two unrelated controls write the
 * same vendor key, so moving one silently moves the other, and neither screen looks wrong.
 *
 * Read reflectively rather than listed by hand — a list would need editing every time a key is
 * added, which is exactly when the check stops being run.
 */
class SettingKeysTest {

    private val keys: Map<String, String> = SettingKeys::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .onEach { it.isAccessible = true }
        .associate { it.name to (it.get(null) as String) }

    @Test
    fun theTableIsNotEmpty() {
        // A reflection filter that matched nothing would make every other test here vacuous.
        assertTrue("no String constants found on SettingKeys", keys.size > 50)
    }

    @Test
    fun noTwoConstantsShareAKeyname() {
        val byValue = keys.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }

        assertEquals("duplicate vendor keynames: $byValue", emptyMap<String, List<String>>(), byValue)
    }

    @Test
    fun keynamesAreNotAccidentallyEqualIgnoringCase() {
        // The vendor's own casing is inconsistent (Sys_, sys_, SYS_) and the provider is
        // case-sensitive, so two constants differing only in case are far more likely to be a
        // transcription slip than two real keys.
        val byLowercase = keys.entries
            .groupBy({ it.value.lowercase() }, { it.key })
            .filterValues { it.size > 1 }

        assertEquals("keynames differing only in case: $byLowercase", emptyMap<String, List<String>>(), byLowercase)
    }

    @Test
    fun vendorStringsMatchSysProviderOpt() {
        // Exact keynames from eventcenter SysProviderOpt.java (line numbers in the comment).
        val expected = mapOf(
            "UI_NUMBER_KEY" to "Sys_UINumber", // :458, was the helper name "uiNumberKey"
            "AUTO_SCREENSAVER_TIME" to "SYS_AUTO_START_SCREENSAVER_TIME", // :234
            "AUTO_CLOSE_SCREEN_TIME" to "SYS_AUTO_START_CLOSE_SCREEN_TIME", // :233
            "SCREEN_OFF_WHEN_ACC_CHANGE" to "Sys_Screen_Off_When_Acc_Change", // :400
            "NAVIBAR_HEIGHT" to "Sys_Customer_NaviBar_Height_Key", // :283
            "LANDSCAPE" to "Sys_Landscape", // :335
            "SET_DAY_LIGHT" to "Set_Day_Light", // :122
            "SET_NIGHT_LIGHT" to "Set_Night_Light", // :144
            "VEHICLE_SERIES" to "Sys_Vehicle_deries", // :275
            "CAR_INFO_ID" to "Sys_CarInfor_ID", // :268
            "CAN_SUPPLIER_ID" to "Sys_camry_air_Supplier_id", // :260
            "SLEEP_TIME" to "SYS_SLEEP_TIME", // :425
            "ACC_ON_DELAY" to "SET_ACC_ON_DELAY", // :65
        )

        expected.forEach { (name, value) -> assertEquals(name, value, keys[name]) }
    }

    @Test
    fun keynamesAreCleanTokens() {
        // These go straight into a `content update --where keyname='…'` line. Whitespace or a quote
        // would mean a transcription error survived all the way to a root shell.
        keys.forEach { (name, value) ->
            assertTrue("$name is blank", value.isNotBlank())
            assertEquals("$name has surrounding whitespace", value.trim(), value)
            assertTrue("$name contains whitespace", value.none { it.isWhitespace() })
            assertTrue("$name contains a quote", value.none { it == '\'' || it == '"' })
        }
    }
}
