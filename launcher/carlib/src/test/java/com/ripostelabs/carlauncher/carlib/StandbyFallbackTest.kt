package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A standby that was never left, read against the PMIC's power-on reason (car dmesg, 2026-10-01/02). */
class StandbyFallbackTest {

    private fun reason(line: String) = StandbyFallback.PowerOn.parse(listOf("[    0.81] qpnp-power-on: PMIC@SID0: $line"))

    @Test
    fun readsThePmicPowerOnReason() {
        assertEquals(StandbyFallback.PowerOn.KEY, reason("Power-on reason: Triggered from KPD (Power Key Press) and 'cold' boot"))
        assertEquals(StandbyFallback.PowerOn.RESET, reason("Power-on reason: Triggered from Hard Reset and 'cold' boot"))
        assertEquals(StandbyFallback.PowerOn.POWER_LOSS, reason("Power-on reason: Triggered from SMPL (Sudden Momentary Power Loss) and 'cold' boot"))
        assertEquals(StandbyFallback.PowerOn.UNKNOWN, StandbyFallback.PowerOn.parse(emptyList()))
    }

    // RST is the owner's only way out of a black panel: the wake failed, so standby turns off.
    @Test
    fun aResetAfterStandbyIsAFailedWake() {
        assertTrue(StandbyFallback.failedWake(StandbyFallback.Left.NEVER, StandbyFallback.PowerOn.RESET))
    }

    // The MCU cuts B+ after the sleep time and powers up with the key: a long park, not a failure.
    @Test
    fun aLongParkOrACrankKeepsStandby() {
        assertFalse(StandbyFallback.failedWake(StandbyFallback.Left.NEVER, StandbyFallback.PowerOn.KEY))
        assertFalse(StandbyFallback.failedWake(StandbyFallback.Left.NEVER, StandbyFallback.PowerOn.POWER_LOSS))
        assertFalse(StandbyFallback.failedWake(StandbyFallback.Left.NEVER, StandbyFallback.PowerOn.UNKNOWN))
    }

    @Test
    fun aResetOutsideStandbyKeepsStandby() {
        assertFalse(StandbyFallback.failedWake(StandbyFallback.Left.YES, StandbyFallback.PowerOn.RESET))
    }
}
