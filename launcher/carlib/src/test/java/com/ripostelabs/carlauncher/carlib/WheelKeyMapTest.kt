package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SysVar `wheel_key_learn_custom` JSON is written by the vendor learn app and read by the
 * gateway with Gson; we parse it read-only. Unusable entries are skipped, never guessed.
 */
class WheelKeyMapTest {

    @Test
    fun parsesTheVendorShape() {
        val map = WheelKeyMap.parse(
            """{"svg_wheel_next_c":"1","svg_wheel_pre_c":"2","svg_wheel_mode_home":"0"}""",
        )
        assertEquals(WheelFunction.HOME, map.functionOf(0))
        assertEquals(WheelFunction.NEXT, map.functionOf(1))
        assertEquals(WheelFunction.PREV, map.functionOf(2))
        assertEquals(1, map.slotOf(WheelFunction.NEXT))
        assertNull(map.functionOf(3))
        assertEquals(
            listOf(0 to WheelFunction.HOME, 1 to WheelFunction.NEXT, 2 to WheelFunction.PREV),
            map.entries,
        )
    }

    @Test
    fun malformedJsonIsEmpty() {
        assertTrue(WheelKeyMap.parse("1").isEmpty) // the v2.4.2 scalar that crash-looped the gateway
        assertTrue(WheelKeyMap.parse("{not json").isEmpty)
        assertTrue(WheelKeyMap.parse("[1,2]").isEmpty)
        assertTrue(WheelKeyMap.parse("").isEmpty)
        assertTrue(WheelKeyMap.parse(null).isEmpty)
        assertEquals(WheelKeyMap.EMPTY, WheelKeyMap.parse("{}"))
    }

    @Test
    fun unknownIconIdsAreSkipped() {
        val map = WheelKeyMap.parse("""{"svg_wheel_mode_teleport":"3","svg_wheel_next_c":"4"}""")
        assertNull(map.functionOf(3))
        assertEquals(WheelFunction.NEXT, map.functionOf(4))
    }

    @Test
    fun badSlotsAreSkipped() {
        val map = WheelKeyMap.parse(
            """{"svg_wheel_next_c":"x","svg_wheel_pre_c":"15","svg_wheel_mode_ok":"-1","svg_wheel_s_add":" 5 "}""",
        )
        assertEquals(listOf(5 to WheelFunction.VOLUME_UP), map.entries)
    }

    @Test
    fun numericJsonValueIsAccepted() {
        // Gson would write a string; tolerate a bare number too.
        val map = WheelKeyMap.parse("""{"svg_wheel_mode_c":7}""")
        assertEquals(WheelFunction.MODE, map.functionOf(7))
    }

    @Test
    fun lparamIsSlotPlusOne() {
        assertEquals(0, WheelKeyMap.slotOfLparam(1))
        assertEquals(9, WheelKeyMap.slotOfLparam(10))
    }

    @Test
    fun everyGatewayFieldHasAFunction() {
        // The field names of base/WheelCustomKey.java that the gateway maps to a function.
        val gateway = listOf(
            "svg_wheel_mode_c", "svg_wheel_next_c", "svg_wheel_pre_c", "svg_wheel_gj",
            "svg_wheel_dh", "svg_wheel_jy", "svg_wheel_gd", "svg_wheel_jt", "svg_wheel_s_add",
            "svg_wheel_s_j", "svg_wheel_mode_voice", "svg_wheel_mode_360", "svg_wheel_mode_fm",
            "svg_wheel_mode_back", "svg_wheel_mode_home", "svg_wheel_mode_ok",
            "svg_wheel_mode_video", "svg_wheel_mode_music",
        )
        for (id in gateway) {
            assertEquals(id, WheelFunction.byIconId(id)?.iconId)
        }
    }
}
