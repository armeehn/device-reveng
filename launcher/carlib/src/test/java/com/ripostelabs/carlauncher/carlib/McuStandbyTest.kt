package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [McuSleepWake] driving its [McuSleepWake.Standby]: stock's accOff when ACC goes off, a sleep key
 * once the MCU's own POWER press has passed, stock's accOn when ACC comes back.
 */
class McuStandbyTest {

    private class FakeAcc(var reading: McuSleepWake.Acc?) : McuSleepWake.AccSource {
        override fun read() = reading
    }

    private class FakePort : McuSleepWake.Port {
        override fun open() {}
        override fun close() {}
        override fun send(frame: ByteArray) {}
        override fun lastMode(): McuOwnerProtocol.Mode? = null
    }

    private class FakeStandby : McuSleepWake.Standby {
        val log = mutableListOf<String>()

        override fun enter() { log.add("enter") }
        override fun leave() { log.add("leave") }
        override fun darken() { log.add("darken") }
    }

    private val acc = FakeAcc(McuSleepWake.Acc.OFF)
    private val standby = FakeStandby()
    private val m = McuSleepWake(FakePort(), acc, standby = standby)

    private val closed = McuSleepWake.PORT_CLOSE_DELAY_MS
    private val dark = McuSleepWake.DARKEN_DELAY_MS

    @Test
    fun accOffEntersStandbyOnce() {
        m.poll(0)
        m.poll(closed)
        m.poll(closed + 1)

        assertEquals(McuSleepWake.State.ASLEEP, m.state)
        assertEquals(listOf("enter"), standby.log)
    }

    @Test
    fun darkensOnceAfterTheMcuPowerPress() {
        m.poll(0)
        m.poll(closed)
        m.poll(dark - 1)
        assertEquals("the MCU presses POWER ~3 s after ACC off", listOf("enter"), standby.log)

        m.poll(dark)
        m.poll(dark + McuSleepWake.POLL_MS)
        assertEquals(listOf("enter", "darken"), standby.log)
    }

    @Test
    fun accOnLeavesStandbyWithoutADarken() {
        m.poll(0)
        m.poll(closed)

        acc.reading = McuSleepWake.Acc.ON
        m.poll(closed + McuSleepWake.POLL_MS)
        assertEquals(McuSleepWake.State.WAKING, m.state)

        m.poll(dark)
        assertEquals(listOf("enter", "leave"), standby.log)
    }

    @Test
    fun powerKeyWhileAsleepDoesNotSleepTheWake() {
        m.poll(0)
        m.poll(closed)
        // The MCU's panel POWER key follows ACC off by a few seconds (car, 2026-09-28 10:55).
        m.powerKey(closed + 2_000)

        val wokeAt = closed + 20_000
        acc.reading = McuSleepWake.Acc.ON
        m.poll(wokeAt)
        m.poll(wokeAt + McuSleepWake.RELOAD_DELAY_MS)

        assertEquals(McuSleepWake.State.AWAKE, m.state)
        assertEquals(listOf("enter", "leave"), standby.log)
    }
}
