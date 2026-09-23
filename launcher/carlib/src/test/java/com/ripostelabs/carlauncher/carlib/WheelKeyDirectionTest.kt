package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.HiworldCanDecoder.SwcAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frame-0x11 id → [WheelKey] direction follows the car, not the vendor constants.
 *
 * [HiworldCanDecoder.swcAction] already carries the direction pressed in the car on
 * 2026-09-07 (8/13 are NEXT, 9/14 are PREV); on Riposte OS 0.2 the gesture engine is the only
 * key path, so its table must say the same or the wheel runs backwards (seen 2026-09-23).
 */
class WheelKeyDirectionTest {
    @Test
    fun `next and prev ids follow the car`() {
        assertEquals(WheelKey.NEXT, WheelKey.fromCanId(8))
        assertEquals(WheelKey.NEXT, WheelKey.fromCanId(13))
        assertEquals(WheelKey.PREV, WheelKey.fromCanId(9))
        assertEquals(WheelKey.PREV, WheelKey.fromCanId(14))
    }

    @Test
    fun `gesture table agrees with the decoder table`() {
        for (id in WheelKey.NEXT.canIds) {
            assertEquals("id $id", SwcAction.NEXT, HiworldCanDecoder.swcAction(id))
        }
        for (id in WheelKey.PREV.canIds) {
            assertEquals("id $id", SwcAction.PREV, HiworldCanDecoder.swcAction(id))
        }
    }
}
