package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The gateway keys off the exact action and extra name; a typo here is a silent no-op. */
class VendorCanKeyTest {

    @Test
    fun voicePressIsTheCanAppsBroadcast() {
        val spec = VendorCanKey.press(WheelKey.VOICE)
        assertEquals("com.choiceway.eventcenter.EventUtils.ZXW_CAN_KEY_EVT", spec.action)
        assertEquals(
            mapOf("com.choiceway.eventcenter.EventUtils.ZXW_CAN_KEY_EVT_EXTRA" to 116),
            spec.ints,
        )
        assertNull(spec.packageName)
    }
}
