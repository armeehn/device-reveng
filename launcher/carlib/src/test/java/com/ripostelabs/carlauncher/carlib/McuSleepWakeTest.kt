package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ACC machine against a scripted [McuSleepWake.AccSource] and a hand-advanced clock; the
 * port records open/close/send in order. No thread and no sleep: [McuSleepWake.poll] is called
 * with the millisecond the vendor's poll would have fired.
 */
class McuSleepWakeTest {

    private class FakeAcc(var reading: McuSleepWake.Acc?) : McuSleepWake.AccSource {
        override fun read() = reading
    }

    private class FakePort(var lastMode: McuOwnerProtocol.Mode? = null) : McuSleepWake.Port {
        val log = mutableListOf<String>()
        val sent = mutableListOf<ByteArray>()

        override fun open() { log.add("open") }
        override fun close() { log.add("close") }
        override fun send(frame: ByteArray) { log.add("send"); sent.add(frame) }
        override fun lastMode() = lastMode
    }

    private val config = McuOwnerProtocol.StartupConfig()

    private fun machine(acc: FakeAcc, port: FakePort) = McuSleepWake(port, acc, config)

    private val second = McuSleepWake.POLL_MS

    @Test
    fun accOffSendsBtStateZeroThenClosesAfterVendorDelay() {
        val acc = FakeAcc(McuSleepWake.Acc.OFF)
        val port = FakePort()
        val m = machine(acc, port)

        m.poll(0)
        assertEquals(McuSleepWake.State.SLEEPING, m.state)
        assertEquals(listOf("send"), port.log)
        assertArrayEquals(McuOwnerProtocol.btState(0), port.sent[0])

        m.poll(second)
        assertEquals("1 s is short of the 1.5 s close", McuSleepWake.State.SLEEPING, m.state)

        m.poll(McuSleepWake.PORT_CLOSE_DELAY_MS)
        assertEquals(McuSleepWake.State.ASLEEP, m.state)
        assertEquals(listOf("send", "close"), port.log)
    }

    @Test
    fun accOnReopensThenReloadsAfterThreeSeconds() {
        val acc = FakeAcc(McuSleepWake.Acc.OFF)
        val port = FakePort(lastMode = McuOwnerProtocol.Mode.RADIO)
        val m = machine(acc, port)
        m.poll(0)
        m.poll(McuSleepWake.PORT_CLOSE_DELAY_MS)
        port.log.clear()
        port.sent.clear()

        acc.reading = McuSleepWake.Acc.ON
        val wokeAt = 10 * second
        m.poll(wokeAt)
        assertEquals(McuSleepWake.State.WAKING, m.state)
        assertEquals(listOf("open"), port.log)

        m.poll(wokeAt + second)
        assertEquals("reload waits the vendor's 3 s", listOf("open"), port.log)

        m.poll(wokeAt + McuSleepWake.RELOAD_DELAY_MS)
        assertEquals(McuSleepWake.State.AWAKE, m.state)
        val expected = McuOwnerProtocol.reload(config, McuOwnerProtocol.Mode.RADIO)
        assertEquals(expected.size, port.sent.size)
        expected.forEachIndexed { i, frame -> assertArrayEquals("frame $i", frame, port.sent[i]) }
    }

    /** AccObserver's mCheckDelay: ACC off within 10 polls of ACC on is not an event. */
    @Test
    fun accOffIsIgnoredForTenSecondsAfterWake() {
        val acc = FakeAcc(McuSleepWake.Acc.OFF)
        val port = FakePort()
        val m = machine(acc, port)
        m.poll(0)
        m.poll(McuSleepWake.PORT_CLOSE_DELAY_MS)

        acc.reading = McuSleepWake.Acc.ON
        val wokeAt = 20 * second
        m.poll(wokeAt)
        m.poll(wokeAt + McuSleepWake.RELOAD_DELAY_MS)
        assertEquals(McuSleepWake.State.AWAKE, m.state)
        port.log.clear()

        acc.reading = McuSleepWake.Acc.OFF
        m.poll(wokeAt + McuSleepWake.WAKE_GUARD_MS - second)
        assertEquals(McuSleepWake.State.AWAKE, m.state)
        assertTrue(port.log.isEmpty())

        m.poll(wokeAt + McuSleepWake.WAKE_GUARD_MS)
        assertEquals(McuSleepWake.State.SLEEPING, m.state)
    }

    @Test
    fun accBackBeforeTheCloseStillCyclesThePort() {
        val acc = FakeAcc(McuSleepWake.Acc.OFF)
        val port = FakePort()
        val m = machine(acc, port)
        m.poll(0)

        acc.reading = McuSleepWake.Acc.ON
        m.poll(second)

        assertEquals(McuSleepWake.State.WAKING, m.state)
        assertEquals(listOf("send", "close", "open"), port.log)
    }

    /** An empty property read is not an event (AccObserver.java:24); nothing moves. */
    @Test
    fun unreadableAccHoldsState() {
        val acc = FakeAcc(null)
        val port = FakePort()
        val m = machine(acc, port)

        m.poll(0)
        m.poll(60 * second)

        assertEquals(McuSleepWake.State.AWAKE, m.state)
        assertTrue(port.log.isEmpty())
    }

    /** powerOff arms msg 289 for 6 s (EventService.java:2704-2708, CustomStatusbar.time), then setAccSleep. */
    @Test
    fun powerKeySleepsSixSecondsLater() {
        val acc = FakeAcc(McuSleepWake.Acc.ON)
        val port = FakePort()
        val m = machine(acc, port)

        m.powerKey(0)
        m.poll(McuSleepWake.POWER_KEY_SLEEP_DELAY_MS - second)
        assertEquals(McuSleepWake.State.AWAKE, m.state)
        assertTrue(port.log.isEmpty())

        m.poll(McuSleepWake.POWER_KEY_SLEEP_DELAY_MS)
        assertEquals(McuSleepWake.State.SLEEPING, m.state)
        assertArrayEquals(McuOwnerProtocol.btState(0), port.sent.single())

        m.poll(McuSleepWake.POWER_KEY_SLEEP_DELAY_MS + McuSleepWake.PORT_CLOSE_DELAY_MS)
        assertEquals(McuSleepWake.State.ASLEEP, m.state)
        assertEquals(listOf("send", "close"), port.log)
    }

    /**
     * AccObserver fires on a change of the property, never on its level (AccObserver.java:24-35):
     * after a power-key sleep the port stays closed until ACC reads off and then on again.
     */
    @Test
    fun powerKeySleepWakesOnlyOnAnAccEdge() {
        val acc = FakeAcc(McuSleepWake.Acc.ON)
        val port = FakePort()
        val m = machine(acc, port)
        m.powerKey(0)
        m.poll(McuSleepWake.POWER_KEY_SLEEP_DELAY_MS)
        m.poll(McuSleepWake.POWER_KEY_SLEEP_DELAY_MS + McuSleepWake.PORT_CLOSE_DELAY_MS)
        port.log.clear()

        m.poll(60 * second)
        assertEquals("ACC still reads on: no edge, no wake", McuSleepWake.State.ASLEEP, m.state)
        assertTrue(port.log.isEmpty())

        acc.reading = McuSleepWake.Acc.OFF
        m.poll(61 * second)
        assertEquals(McuSleepWake.State.ASLEEP, m.state)

        acc.reading = McuSleepWake.Acc.ON
        m.poll(62 * second)
        assertEquals(McuSleepWake.State.WAKING, m.state)
        assertEquals(listOf("open"), port.log)
    }

    /**
     * Msg 289 fires 6 s after the key, so a key during the wake lets the 3 s reload run first;
     * only then does setAccSleep sleep the unit again (:841-845).
     */
    @Test
    fun powerKeyWhileWakingSleepsAfterTheReload() {
        val acc = FakeAcc(McuSleepWake.Acc.OFF)
        val port = FakePort(lastMode = McuOwnerProtocol.Mode.RADIO)
        val m = machine(acc, port)
        m.poll(0)
        m.poll(McuSleepWake.PORT_CLOSE_DELAY_MS)
        acc.reading = McuSleepWake.Acc.ON
        val wokeAt = 10 * second
        m.poll(wokeAt)
        assertEquals(McuSleepWake.State.WAKING, m.state)
        port.log.clear()
        port.sent.clear()

        val keyAt = wokeAt + second
        m.powerKey(keyAt)
        m.poll(wokeAt + McuSleepWake.RELOAD_DELAY_MS)
        assertEquals(McuSleepWake.State.AWAKE, m.state)
        assertEquals(McuOwnerProtocol.reload(config, McuOwnerProtocol.Mode.RADIO).size, port.sent.size)
        port.sent.clear()

        m.poll(keyAt + McuSleepWake.POWER_KEY_SLEEP_DELAY_MS - second)
        assertEquals("inside the wake guard, the key still counts", McuSleepWake.State.AWAKE, m.state)
        m.poll(keyAt + McuSleepWake.POWER_KEY_SLEEP_DELAY_MS)
        assertEquals(McuSleepWake.State.SLEEPING, m.state)
        assertArrayEquals(McuOwnerProtocol.btState(0), port.sent.single())
    }
}
