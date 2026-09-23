package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.DozeGuard.Action
import com.ripostelabs.carlauncher.carlib.McuSleepWake.Acc
import com.ripostelabs.carlauncher.carlib.McuSleepWake.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The GSI dozed the panel 2 s after the launcher started with ACC on (car, 2026-09-23). The
 * guard wakes it then, and only then: a sleep McuSleepWake asked for, or ACC off, is left alone.
 * A dream and a screen-off take the same decision, so each case names the one that motivates it.
 */
class DozeGuardTest {

    @Test
    fun dreamWhileAccOnWakesThePanel() {
        assertEquals(Action.WAKE, DozeGuard.decide(State.AWAKE, Acc.ON))
    }

    @Test
    fun screenOffWhileAccOnWakesThePanel() {
        assertEquals(Action.WAKE, DozeGuard.decide(State.AWAKE, Acc.ON))
    }

    @Test
    fun sleepRequestedByUsIsLeftAlone() {
        assertEquals(Action.LEAVE, DozeGuard.decide(State.SLEEPING, Acc.ON))
        assertEquals(Action.LEAVE, DozeGuard.decide(State.ASLEEP, Acc.OFF))
    }

    @Test
    fun screenOffWithAccOffIsLeftAlone() {
        // ACC off read before the 1 s poll moved the machine: still not ours to wake.
        assertEquals(Action.LEAVE, DozeGuard.decide(State.AWAKE, Acc.OFF))
    }

    @Test
    fun unreadableAccHoldsTheMachineState() {
        assertEquals(Action.WAKE, DozeGuard.decide(State.WAKING, null))
        assertEquals(Action.LEAVE, DozeGuard.decide(State.ASLEEP, null))
    }

    @Test
    fun noMachineMeansNoOwnerAndNoWake() {
        assertEquals(Action.LEAVE, DozeGuard.decide(null, Acc.ON))
    }
}
