package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.McuSleepWake.Acc
import com.ripostelabs.carlauncher.carlib.McuSleepWake.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ACC from the MCU's SYS_EVENT line, with the panel waking standing in for ACC on while the port
 * is shut. The latest of the two wins.
 */
class CarAccTest {

    private fun event(acc: Boolean) = McuOwnerProtocol.SysEvent(
        disc = false,
        usb = false,
        rightTurn = false,
        illumination = false,
        brake = false,
        reverse = false,
        accLine = acc,
        mcan = false,
        startStop = false,
        hdmi = false,
        leftTurn = false,
    )

    @Test
    fun nothingReadBeforeTheFirstEvent() {
        assertNull(CarAcc().read())
    }

    @Test
    fun followsTheMcuLine() {
        val acc = CarAcc()

        acc.onSysEvent(event(acc = true))
        assertEquals(Acc.ON, acc.read())

        acc.onSysEvent(event(acc = false))
        assertEquals(Acc.OFF, acc.read())
    }

    @Test
    fun panelWakeAfterAccOffReadsOn() {
        val acc = CarAcc()
        acc.onSysEvent(event(acc = false))

        acc.onScreenOn()
        assertEquals(Acc.ON, acc.read())

        acc.onSysEvent(event(acc = false))
        assertEquals("the reopened port says ACC is still off", Acc.OFF, acc.read())
    }

    @Test
    fun dozeGuardLeavesThePanelDarkOnceTheMcuSaysAccOff() {
        // Car, 2026-09-28 10:55: acc=false, the MCU's POWER press, then DozeGuard woke the panel
        // because sys.gotoSleep.state is never written on the GSI.
        val acc = CarAcc()
        acc.onSysEvent(event(acc = false))

        assertEquals(DozeGuard.Action.LEAVE, DozeGuard.decide(State.AWAKE, acc.read()))
    }
}
