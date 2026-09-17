package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Sys_CurBreakSate` as the vendor computed it (EventService.java:536-541), announced on change
 * only. RAV4-98: the suite's video gate reads this row.
 */
class SysVarMirrorTest {

    private val changes = mutableListOf<Pair<String, String>>()

    private var detect = true

    private val mirror = SysVarMirror(detect = { detect }) { k, v -> changes += k to v }

    private fun sys(brake: Boolean) = McuOwnerProtocol.SysEvent(
        disc = false, usb = false, rightTurn = false, illumination = false, brake = brake,
        reverse = false, accLine = false, mcan = false, startStop = false, hdmi = false, leftTurn = false,
    )

    @Test
    fun ruleMatchesTheVendor() {
        assertEquals("1", SysVarMirror.brakeState(detect = true, connected = false))
        assertEquals("0", SysVarMirror.brakeState(detect = true, connected = true))
        assertEquals("0", SysVarMirror.brakeState(detect = false, connected = false))
        assertEquals("0", SysVarMirror.brakeState(detect = false, connected = true))
    }

    @Test
    fun emptyUntilTheFirstSysEvent() {
        assertNull(mirror.get(SysVarMirror.KEY_CUR_BRAKE_STATE))
        assertEquals(emptyList<Pair<String, String>>(), changes)
    }

    @Test
    fun releasingTheHandbrakeGatesAndRepeatsAreSilent() {
        mirror.onSysEvent(sys(brake = true))
        mirror.onSysEvent(sys(brake = true))
        assertEquals("0", mirror.get(SysVarMirror.KEY_CUR_BRAKE_STATE))
        assertEquals(listOf(SysVarMirror.KEY_CUR_BRAKE_STATE to "0"), changes)

        mirror.onSysEvent(sys(brake = false))
        mirror.onSysEvent(sys(brake = false))
        assertEquals("1", mirror.get(SysVarMirror.KEY_CUR_BRAKE_STATE))
        assertEquals(2, changes.size)
        assertEquals(SysVarMirror.KEY_CUR_BRAKE_STATE to "1", changes.last())
    }

    @Test
    fun detectionOffOpensTheGateWhateverTheLine() {
        detect = false
        mirror.onSysEvent(sys(brake = false))
        assertEquals("0", mirror.get(SysVarMirror.KEY_CUR_BRAKE_STATE))
    }

    @Test
    fun refreshReappliesDetectionToTheLastEvent() {
        mirror.refresh()
        assertEquals(emptyList<Pair<String, String>>(), changes)   // nothing seen yet: no row

        mirror.onSysEvent(sys(brake = false))
        assertEquals("1", mirror.get(SysVarMirror.KEY_CUR_BRAKE_STATE))

        detect = false
        mirror.refresh()
        assertEquals("0", mirror.get(SysVarMirror.KEY_CUR_BRAKE_STATE))

        detect = true
        mirror.refresh()
        assertEquals("1", mirror.get(SysVarMirror.KEY_CUR_BRAKE_STATE))
        assertEquals(3, changes.size)
    }
}
