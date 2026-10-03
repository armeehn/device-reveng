package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StandbyOptIn] in front of [McuSleepWake]: with the setting off, ACC off runs none of
 * [AccStandby]'s commands and the port stays as it was before standby; with it on, the machine
 * runs exactly the sequence it runs without the gate.
 */
class StandbyOptInTest {

    private class FakeAcc(var reading: McuSleepWake.Acc?) : McuSleepWake.AccSource {
        override fun read() = reading
    }

    /** Port and standby write to one log, so the order of the whole sequence is compared. */
    private class Recorder : McuSleepWake.Port, McuSleepWake.Standby {
        val log = mutableListOf<String>()

        override fun open() { log.add("open") }
        override fun close() { log.add("close") }
        override fun send(frame: ByteArray) { log.add("send ${frame.joinToString(" ") { "%02X".format(it) }}") }
        override fun lastMode(): McuOwnerProtocol.Mode? = null
        override fun enter() { log.add("enter") }
        override fun leave() { log.add("leave") }
        override fun darken() { log.add("darken") }
    }

    private val standbyCommands = setOf("enter", "leave", "darken")

    private var mode = StandbyMode.OFF
    private val gate = StandbyOptIn { mode }

    /** ACC off long enough to close the port and darken, then ACC on through the reload. */
    private fun drive(m: McuSleepWake, acc: FakeAcc) {
        acc.reading = McuSleepWake.Acc.OFF
        var now = 0L
        while (now <= McuSleepWake.DARKEN_DELAY_MS + McuSleepWake.POLL_MS) {
            m.poll(now)
            now += McuSleepWake.POLL_MS
        }

        acc.reading = McuSleepWake.Acc.ON
        val until = now + McuSleepWake.RELOAD_DELAY_MS + McuSleepWake.POLL_MS
        while (now <= until) {
            m.poll(now)
            now += McuSleepWake.POLL_MS
        }
    }

    private fun gated(rec: Recorder, acc: FakeAcc) =
        McuSleepWake(rec, gate.acc(acc), standby = gate.standby(rec))

    @Test
    fun defaultIsOff() {
        assertEquals(StandbyMode.OFF, StandbyMode.of(null))
        assertEquals(StandbyMode.OFF, StandbyMode.of("garbage"))
        assertEquals(StandbyMode.ON, StandbyMode.of("ON"))
    }

    @Test
    fun offAccOffRunsNoStandbyAndLeavesThePort() {
        val rec = Recorder()
        val acc = FakeAcc(null)
        val m = gated(rec, acc)

        drive(m, acc)

        assertEquals("no standby command, no BT 0, no close: the MCU just cuts power", emptyList<String>(), rec.log)
        assertEquals(McuSleepWake.State.AWAKE, m.state)
    }

    @Test
    fun offDozeGuardStillWakesThePanel() {
        val acc = FakeAcc(McuSleepWake.Acc.OFF)

        val reading = gate.acc(acc).read()

        assertEquals(DozeGuard.Action.WAKE, DozeGuard.decide(McuSleepWake.State.AWAKE, reading))
    }

    @Test
    fun offPowerKeyKeepsThePortSleepWithoutStandby() {
        val rec = Recorder()
        val acc = FakeAcc(McuSleepWake.Acc.OFF)
        val m = gated(rec, acc)

        m.powerKey(0)
        var now = 0L
        while (now <= McuSleepWake.POWER_KEY_SLEEP_DELAY_MS + McuSleepWake.DARKEN_DELAY_MS + McuSleepWake.POLL_MS) {
            m.poll(now)
            now += McuSleepWake.POLL_MS
        }

        assertEquals(McuSleepWake.State.ASLEEP, m.state)
        assertEquals(emptyList<String>(), rec.log.filter { it in standbyCommands })
        assertEquals(listOf("close"), rec.log.filter { it == "close" })
    }

    @Test
    fun onRunsTheUngatedSequenceUnchanged() {
        mode = StandbyMode.ON
        val plain = Recorder()
        val plainAcc = FakeAcc(null)
        drive(McuSleepWake(plain, plainAcc, standby = plain), plainAcc)

        val rec = Recorder()
        val acc = FakeAcc(null)
        drive(gated(rec, acc), acc)

        assertEquals(plain.log, rec.log)
        assertEquals(listOf("enter", "darken", "leave"), rec.log.filter { it in standbyCommands })
    }

    @Test
    fun turnedOffWhileAsleepStillLeaves() {
        mode = StandbyMode.ON
        val rec = Recorder()
        val acc = FakeAcc(McuSleepWake.Acc.OFF)
        val m = gated(rec, acc)
        m.poll(0)
        m.poll(McuSleepWake.PORT_CLOSE_DELAY_MS)

        mode = StandbyMode.OFF
        acc.reading = McuSleepWake.Acc.ON
        m.poll(McuSleepWake.PORT_CLOSE_DELAY_MS + McuSleepWake.POLL_MS)

        assertEquals("a standby entered is always left", listOf("enter", "leave"), rec.log.filter { it in standbyCommands })
    }
}
