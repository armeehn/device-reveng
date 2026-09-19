package com.ripostelabs.carlauncher.carlib

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session against a fake link: what is written, in what order, and what the listener sees.
 * Timing constants are shortened; the vendor's 3 × 500 ms is asserted by count, not by clock.
 */
class McuOwnerTest {

    private companion object {
        /** start() hands the port to its own thread; anything slower is the main thread waiting on it. */
        const val BOUNDED_START_MS = 200L
        const val TEST_TIMEOUT_MS = 20_000L
        const val WATCHDOG_OFF_MS = 60_000L
        const val WAIT_FOR_MS = 10_000L
        // The production window. 20 ms once kept the no-ack cases quick, but a loaded runner
        // answered the fake link's ack after the window, the owner re-sent SRC_NULL, the fake
        // acked twice, and every exact frame count and write index drifted: three different
        // pairs of cases went red on CI in one day. The no-ack cases now cost ACK_ATTEMPTS×0.5 s.
        const val ACK_MS = McuOwnerProtocol.ACK_TIMEOUT_MS
        const val SLOW_START_MS = 100L
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** A pipe whose reads block on a queue and whose writes are recorded; [ackNull] answers SRC_NULL. */
    private class FakeLink(private val ackNull: Boolean) : McuLink {
        val written = CopyOnWriteArrayList<ByteArray>()
        private val inbound = LinkedBlockingQueue<ByteArray>()

        @Volatile
        var closed = false

        fun feed(frame: ByteArray) {
            inbound.put(frame)
        }

        override fun read(buffer: ByteArray): Int {
            while (!closed) {
                val next = inbound.poll(10, TimeUnit.MILLISECONDS) ?: continue
                next.copyInto(buffer)
                return next.size
            }
            return -1
        }

        override fun write(bytes: ByteArray) {
            written.add(bytes.copyOf())
            if (ackNull && bytes.contentEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.NULL))) {
                feed(McuSerial.encode(McuOpcode.MODE_ACK.code, byteArrayOf(McuOwnerProtocol.Mode.NULL.code.toByte())))
            }
        }

        override fun close() {
            closed = true
        }
    }

    private class Gate(private val eventcenter: Boolean, private val enabled: Boolean) : McuOwner.Gate {
        override fun eventcenterPresent() = eventcenter
        override fun ownerEnabled() = enabled
    }

    private class Recorder : McuOwner.Listener {
        val sys = CopyOnWriteArrayList<McuOwnerProtocol.SysEvent>()
        val volume = CopyOnWriteArrayList<McuOwnerProtocol.MainVolume>()
        val keys = CopyOnWriteArrayList<Int>()
        val panel = CopyOnWriteArrayList<McuOwnerProtocol.PanelKey>()
        val wheel = CopyOnWriteArrayList<McuOwnerProtocol.WheelKey>()
        val radio = CopyOnWriteArrayList<McuOwnerProtocol.RadioEvent>()
        val signals = CopyOnWriteArrayList<CanSignal>()
        val other = CopyOnWriteArrayList<McuSerial.Command>()
        val wakes = CopyOnWriteArrayList<Long>()
        val rtc = CopyOnWriteArrayList<LocalDateTime>()

        override fun onSysEvent(event: McuOwnerProtocol.SysEvent) { sys.add(event) }
        override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) { this.volume.add(volume) }
        override fun onKey(key: Int) { keys.add(key) }
        override fun onPanelKey(key: McuOwnerProtocol.PanelKey) { panel.add(key) }
        override fun onWheelKey(key: McuOwnerProtocol.WheelKey) { wheel.add(key) }
        override fun onRadio(event: McuOwnerProtocol.RadioEvent) { radio.add(event) }
        override fun onCanSignal(signal: CanSignal, atMs: Long) { signals.add(signal) }
        override fun onOther(command: McuSerial.Command) { other.add(command) }
        override fun onWake() { wakes.add(System.currentTimeMillis()) }
        override fun onRtc(time: LocalDateTime) { rtc.add(time) }
    }

    // The write watchdog is off the clock here (a CI stall once declared a fake link dead
    // mid-test); the two RAV4-96 cases below shorten it against a write that never returns.
    private fun owner(link: FakeLink, gate: Gate = Gate(eventcenter = false, enabled = true), recorder: Recorder = Recorder()) =
        McuOwner(gate, recorder, openLink = { link }, ackTimeoutMs = ACK_MS, writeTimeoutMs = WATCHDOG_OFF_MS)

    // Generous: the CI runner is shared and has run McuOwnerTest at a load average past 250,
    // where a 2 s bound expired on cases that assert by count, not by clock.
    private fun <T> waitFor(what: String, timeoutMs: Long = WAIT_FOR_MS, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /**
     * start() once assigned [worker] AFTER Thread.start() ran inside apply {}: the new thread's
     * first isCurrent() could read the old field, and run() returned without opening the port.
     * Status stayed Idle for good. On a loaded CI runner that was one "timed out waiting for
     * running" in a few hundred starts; in the car it is an owner path that never comes up.
     *
     * The window is a few instructions wide, so no loop reaches it. A Thread whose start()
     * returns late does: the owner thread runs its first isCurrent() while the caller is
     * still inside apply {}, before the field is written. Correct code assigns first.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun startAssignsTheWorkerBeforeTheThreadRuns() {
        val link = FakeLink(ackNull = true)
        val owner = McuOwner(
            Gate(eventcenter = false, enabled = true),
            Recorder(),
            openLink = { link },
            ackTimeoutMs = 20,
            writeTimeoutMs = WATCHDOG_OFF_MS,
            newThread = { body, name ->
                object : Thread(body, name) {
                    override fun start() {
                        super.start()
                        sleep(SLOW_START_MS)   // the caller has not stored the field yet
                    }
                }
            },
        )
        owner.start()
        waitFor("running after a slow Thread.start()") { owner.status.value as? McuOwner.Status.Running }
        owner.stop()
    }

    @Test
    fun refusesWhileEventcenterIsInstalled() {
        val link = FakeLink(ackNull = true)
        val owner = owner(link, Gate(eventcenter = true, enabled = true))

        owner.start()

        assertTrue(owner.status.value is McuOwner.Status.Blocked)
        assertTrue("the port must never be opened beside the vendor", link.written.isEmpty())
    }

    @Test
    fun refusesUnlessTheOsEnabledIt() {
        val link = FakeLink(ackNull = true)
        val owner = owner(link, Gate(eventcenter = false, enabled = false))

        owner.start()

        assertEquals(McuOwner.Status.Blocked("ro.riposte.os.car_owner is not 1"), owner.status.value)
        assertTrue(link.written.isEmpty())
    }

    @Test
    fun handshakeIsStartupThenNullModeOnce_whenAcked() {
        val link = FakeLink(ackNull = true)
        val owner = owner(link)

        owner.start()
        val running = waitFor("running") { owner.status.value as? McuOwner.Status.Running }
        owner.stop()

        assertTrue(running.acked)
        val expected = McuOwnerProtocol.startup(McuOwnerProtocol.StartupConfig()) + listOf(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.NULL))
        assertEquals(expected.size, link.written.size)
        expected.forEachIndexed { i, frame -> assertArrayEquals("frame $i", frame, link.written[i]) }
    }

    /** No ACK: SRC_NULL goes out ACK_ATTEMPTS times and the session still runs, as the vendor's does. */
    @Test
    fun retriesNullModeThreeTimesAndRunsAnyway() {
        val link = FakeLink(ackNull = false)
        val owner = owner(link)

        owner.start()
        val running = waitFor("running") { owner.status.value as? McuOwner.Status.Running }
        owner.stop()

        assertFalse(running.acked)
        val nullFrame = McuOwnerProtocol.mode(McuOwnerProtocol.Mode.NULL)
        assertEquals(McuOwnerProtocol.ACK_ATTEMPTS, link.written.count { it.contentEquals(nullFrame) })
    }

    @Test
    fun dispatchesSysEventVolumeKeyAndCanRelay() {
        val link = FakeLink(ackNull = true)
        val recorder = Recorder()
        val owner = owner(link, recorder = recorder)
        owner.start()
        waitFor("running") { owner.status.value as? McuOwner.Status.Running }

        // The key goes first: once the reverse bit is seen, POWER is dropped like the vendor drops it.
        link.feed(McuSerial.encode(McuOpcode.KEY_EVENT.code, bytes(McuOwnerProtocol.Key.MODE, 0x00)))
        link.feed(McuSerial.encode(McuOpcode.SYS_EVENT.code, bytes(0x02, 0x00)))
        link.feed(McuSerial.encode(McuOpcode.MAIN_VOLUME.code, bytes(0x15)))
        // The CAN box's 0x32 vehicle-info frame, relayed under 0xA5.
        link.feed(McuSerial.encode(McuSerial.OP_CAN, McuFrame.encode(0x32, bytes(0x00, 0x00, 0x05, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF))))
        link.feed(McuSerial.encode(0x97, bytes(0x01)))

        // Six, not five: the MODE_ACK that answered the handshake is a frame too.
        val counted = waitFor("six frames counted") {
            (owner.status.value as? McuOwner.Status.Running)?.takeIf { it.frames == 6L }
        }
        owner.stop()

        assertTrue(recorder.sys[0].reverse)
        assertEquals(McuOwnerProtocol.MainVolume(21, silent = false), recorder.volume[0])
        assertEquals(McuOwnerProtocol.Key.MODE, recorder.keys[0])
        assertFalse("0x32 must decode to a real signal, not Unknown", recorder.signals[0] is CanSignal.Unknown)
        assertEquals(0x97, recorder.other[0].opcode)
        assertEquals(0L, counted.badChecksum)
    }

    /** A started owner with the handshake's own writes already counted. */
    private fun runningOwner(link: FakeLink, recorder: Recorder): Pair<McuOwner, Int> {
        val owner = owner(link, recorder = recorder)
        owner.start()
        waitFor("running") { owner.status.value as? McuOwner.Status.Running }
        return owner to link.written.size
    }

    /** VOL+ is echoed to the MCU as `08 00` (sendSystemKey) and still reaches the listener. */
    @Test
    fun volumeKeyIsEchoedAsSystemKey() {
        val link = FakeLink(ackNull = true)
        val recorder = Recorder()
        val (owner, handshake) = runningOwner(link, recorder)

        link.feed(McuSerial.encode(McuOpcode.KEY_EVENT.code, bytes(McuOwnerProtocol.Key.VOLUME_UP, 0x00)))
        waitFor("echo written") { link.written.getOrNull(handshake) }
        owner.stop()

        assertArrayEquals(McuOwnerProtocol.systemKey(McuOwnerProtocol.SystemKey.VOLUME_UP), link.written[handshake])
        assertEquals(handshake + 1, link.written.size)
        assertEquals(McuOwnerProtocol.PanelKey(McuOwnerProtocol.Key.VOLUME_UP, 0), recorder.panel.single())
        assertEquals(McuOwnerProtocol.Key.VOLUME_UP, recorder.keys.single())
    }

    /** POWER: notify first, then the vendor's clock stamp and five SRC_POWEROFF frames. */
    @Test
    fun powerKeyRunsPowerOffAfterNotify() {
        val link = FakeLink(ackNull = true)
        val recorder = Recorder()
        val (owner, handshake) = runningOwner(link, recorder)

        link.feed(McuSerial.encode(McuOpcode.KEY_EVENT.code, bytes(McuOwnerProtocol.Key.POWER, 0x00)))
        val expected = 1 + McuOwnerProtocol.POWER_OFF_REPEATS
        waitFor("power-off frames written") { link.written.takeIf { it.size >= handshake + expected } }
        owner.stop()

        assertEquals(handshake + expected, link.written.size)
        assertEquals(0x13, link.written[handshake][3].toInt())
        for (i in 1..McuOwnerProtocol.POWER_OFF_REPEATS) {
            assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.POWER_OFF), link.written[handshake + i])
        }
        assertEquals(McuOwnerProtocol.Key.POWER, recorder.panel.single().code)
    }

    /** With the camera up (71 reverse bit) MODE is dropped and VOL- still passes, as in onCmdKeyEvent. */
    @Test
    fun reverseDropsAllButAudioAndTrackKeys() {
        val link = FakeLink(ackNull = true)
        val recorder = Recorder()
        val (owner, handshake) = runningOwner(link, recorder)

        link.feed(McuSerial.encode(McuOpcode.SYS_EVENT.code, bytes(0x02, 0x00)))
        link.feed(McuSerial.encode(McuOpcode.KEY_EVENT.code, bytes(McuOwnerProtocol.Key.MODE, 0x00)))
        link.feed(McuSerial.encode(McuOpcode.KEY_EVENT.code, bytes(McuOwnerProtocol.Key.VOLUME_DOWN, 0x00)))
        waitFor("echo written") { link.written.getOrNull(handshake) }
        link.feed(McuSerial.encode(McuOpcode.SYS_EVENT.code, bytes(0x00, 0x00)))
        link.feed(McuSerial.encode(McuOpcode.KEY_EVENT.code, bytes(McuOwnerProtocol.Key.MODE, 0x00)))
        waitFor("mode after reverse") { recorder.panel.takeIf { it.size == 2 } }
        owner.stop()

        assertEquals(listOf(McuOwnerProtocol.Key.VOLUME_DOWN, McuOwnerProtocol.Key.MODE), recorder.panel.map { it.code })
        assertArrayEquals(McuOwnerProtocol.systemKey(McuOwnerProtocol.SystemKey.VOLUME_DOWN), link.written[handshake])
    }

    /**
     * The vendor's read thread hands every LEN-delimited frame to the handler without checking
     * CK (SerialReadThread.parseRxData, EventService.parseCmdEvt), and the car's first tally
     * said the outbound formula DISAGREES with what the MCU sends (RAV4-61 item 10). A frame
     * with a CK the launcher cannot reproduce is still the car talking: dispatched, and counted.
     */
    @Test
    fun badChecksumFrameIsDispatchedAndCounted() {
        val link = FakeLink(ackNull = true)
        val recorder = Recorder()
        val (owner, _) = runningOwner(link, recorder)

        val frame = McuSerial.encode(McuOpcode.SYS_EVENT.code, bytes(0x02, 0x00))
        frame[frame.size - 2] = (frame[frame.size - 2].toInt() xor 0x5A).toByte()   // CK is before the pad
        link.feed(frame)

        val counted = waitFor("the bad-CK frame counted") {
            (owner.status.value as? McuOwner.Status.Running)?.takeIf { it.badChecksum == 1L }
        }
        waitFor("the reverse bit seen") { recorder.sys.firstOrNull() }
        owner.stop()

        assertTrue(recorder.sys[0].reverse)
        assertEquals(1L, counted.badChecksum)
    }

    /** `74` goes to onWheelKey and writes nothing back; an unknown `72` code is surfaced, not thrown. */
    @Test
    fun wheelEdgeAndUnknownPanelCodeAreSurfaced() {
        val link = FakeLink(ackNull = true)
        val recorder = Recorder()
        val (owner, handshake) = runningOwner(link, recorder)

        link.feed(McuSerial.encode(McuOpcode.WHEEL_EVENT.code, bytes(0x02, 0x01, 0x00, 0x5A)))
        link.feed(McuSerial.encode(McuOpcode.KEY_EVENT.code, bytes(0xEE, 0x00)))
        waitFor("both dispatched") { recorder.panel.takeIf { it.size == 1 && recorder.wheel.size == 1 } }
        owner.stop()

        assertEquals(McuOwnerProtocol.WheelKey(slot = 2, down = true, voltage = 0x5A), recorder.wheel.single())
        assertEquals(0xEE, recorder.panel.single().code)
        assertEquals(handshake, link.written.size)
        assertTrue(recorder.other.isEmpty())
    }

    /** `73 03 25 9E` is 96.30 MHz; a sub-command the vendor has no case for (9) is an "other". */
    @Test
    fun dispatchesRadioEventsAndLogsUnknownSubCommand() {
        val link = FakeLink(ackNull = true)
        val recorder = Recorder()
        val owner = owner(link, recorder = recorder)
        owner.start()
        waitFor("running") { owner.status.value as? McuOwner.Status.Running }

        link.feed(McuSerial.encode(McuOpcode.RADIO_EVENT.code, bytes(0x03, 0x25, 0x9E)))
        link.feed(McuSerial.encode(McuOpcode.RADIO_EVENT.code, bytes(0x09, 0x01)))

        waitFor("three frames counted") {
            (owner.status.value as? McuOwner.Status.Running)?.takeIf { it.frames == 3L }
        }
        owner.stop()

        assertEquals(McuOwnerProtocol.RadioEvent.Frequency(9630), recorder.radio[0])
        assertEquals(1, recorder.radio.size)
        assertEquals(McuOpcode.RADIO_EVENT.code, recorder.other[0].opcode)
    }

    @Test
    fun linkClosingUnderneathReportsFailed() {
        val link = FakeLink(ackNull = true)
        val owner = owner(link)
        owner.start()
        waitFor("running") { owner.status.value as? McuOwner.Status.Running }

        link.close()

        val failed = waitFor("failed") { owner.status.value as? McuOwner.Status.Failed }
        assertNotNull(failed)
        assertEquals("link closed", failed.reason)
    }

    /** `96 01` reaches onWake; `96 00` is nothing the vendor acts on and lands in onOther. */
    @Test
    fun sleepStateOneDispatchesOnWake() {
        val link = FakeLink(ackNull = true)
        val recorder = Recorder()
        val owner = owner(link, recorder = recorder)
        owner.start()
        waitFor("running") { owner.status.value as? McuOwner.Status.Running }

        link.feed(McuSerial.encode(McuOpcode.SLEEP_STATE.code, bytes(0x01)))
        link.feed(McuSerial.encode(McuOpcode.SLEEP_STATE.code, bytes(0x00)))

        waitFor("both frames") { (owner.status.value as? McuOwner.Status.Running)?.takeIf { it.frames == 3L } }
        owner.stop()

        assertEquals(1, recorder.wakes.size)
        assertEquals(McuOpcode.SLEEP_STATE.code, recorder.other[0].opcode)
    }

    /** The 10 Hz `8E` G-sensor stream is unhandled and still reaches onOther every time. */
    @Test
    fun repeatedUnhandledOpcodeReachesOnOtherEachTime() {
        val link = FakeLink(ackNull = true)
        val recorder = Recorder()
        val owner = owner(link, recorder = recorder)
        owner.start()
        waitFor("running") { owner.status.value as? McuOwner.Status.Running }

        repeat(3) {
            link.feed(McuSerial.encode(McuOpcode.RADAR_3DH.code, bytes(0x00, 0x10, 0x00, 0x20, 0x03, 0xF0)))
        }

        waitFor("three frames") { (owner.status.value as? McuOwner.Status.Running)?.takeIf { it.frames == 4L } }
        owner.stop()

        assertEquals(3, recorder.other.size)
        assertTrue(recorder.other.all { it.opcode == McuOpcode.RADAR_3DH.code })
    }

    /** `83` reaches onRtc decoded, and not onOther. */
    @Test
    fun rtcFrameDispatchesOnRtc() {
        val link = FakeLink(ackNull = true)
        val recorder = Recorder()
        val owner = owner(link, recorder = recorder)
        owner.start()
        waitFor("running") { owner.status.value as? McuOwner.Status.Running }

        link.feed(McuSerial.encode(McuOpcode.SYS_RTC_TIME.code, bytes(26, 9, 19, 14, 5, 7)))

        waitFor("frame") { (owner.status.value as? McuOwner.Status.Running)?.takeIf { it.frames == 2L } }
        owner.stop()

        assertEquals(listOf(LocalDateTime.of(2026, 9, 19, 14, 5, 7)), recorder.rtc)
        assertTrue(recorder.other.isEmpty())
    }

    /** FanOut forwards every callback, the tuner's `73` events and the key edges included. */
    @Test
    fun fanOutForwardsRadioAndKeys() {
        val a = Recorder()
        val b = Recorder()
        val fanOut = McuOwner.FanOut(a, b)
        val freq = McuOwnerProtocol.RadioEvent.Frequency(9630)
        val panel = McuOwnerProtocol.PanelKey(McuOwnerProtocol.Key.RADIO, 0)

        fanOut.onRadio(freq)
        fanOut.onPanelKey(panel)
        fanOut.onWake()

        for (recorder in listOf(a, b)) {
            assertEquals(listOf<McuOwnerProtocol.RadioEvent>(freq), recorder.radio)
            assertEquals(listOf(panel), recorder.panel)
            assertEquals(1, recorder.wakes.size)
        }
    }

    /** setMode remembers its argument so a wake can resume it. */
    @Test
    fun setModeIsRemembered() {
        val link = FakeLink(ackNull = true)
        val owner = owner(link)
        owner.start()
        waitFor("running") { owner.status.value as? McuOwner.Status.Running }

        owner.setMode(McuOwnerProtocol.Mode.RADIO)
        owner.stop()

        assertEquals(McuOwnerProtocol.Mode.RADIO, owner.lastMode)
    }

    /** A pipe whose first write never returns: the port with nobody reading on the far side. */
    private class StuckLink : McuLink {
        val opens = java.util.concurrent.atomic.AtomicInteger()
        private val never = java.util.concurrent.CountDownLatch(1)

        @Volatile
        var closed = false

        override fun read(buffer: ByteArray): Int {
            while (!closed) {
                Thread.sleep(10)
            }
            return -1
        }

        override fun write(bytes: ByteArray) {
            never.await()
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * RAV4-96: with the port wired but no reader behind it, `CharDevLink.write` blocked the main
     * thread from `MainActivity.onCreate` and the launcher ANR'd on every start. start() must
     * hand the port to its own thread and return at once, and the stuck write must surface as
     * a dead link instead of a hang.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun startReturnsAtOnceWhenTheWriteBlocks() {
        val link = StuckLink()
        val owner = McuOwner(Gate(eventcenter = false, enabled = true), Recorder(), openLink = { link.opens.incrementAndGet(); link }, ackTimeoutMs = ACK_MS, writeTimeoutMs = 50)

        val startedAt = System.currentTimeMillis()
        owner.start()
        val took = System.currentTimeMillis() - startedAt

        assertTrue("start() blocked for $took ms", took < BOUNDED_START_MS)
        val failed = waitFor("link dead") { owner.status.value as? McuOwner.Status.Failed }
        assertTrue(failed.reason, failed.reason.startsWith(McuOwner.LINK_DEAD))
        owner.stop()
    }

    /** After a dead link the owner reopens in the background; a live port then runs the session. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun deadLinkIsReopenedInTheBackground() {
        val stuck = StuckLink()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        // A fresh live link per reopen: a starved runner can outlast the 50 ms watchdog on the
        // live link too, and a closed FakeLink never reads again.
        val owner = McuOwner(
            Gate(eventcenter = false, enabled = true),
            Recorder(),
            openLink = { if (opens.getAndIncrement() == 0) stuck else FakeLink(ackNull = true) },
            ackTimeoutMs = ACK_MS,
            writeTimeoutMs = 50,
            retryDelayMs = 50,
        )

        owner.start()
        val running = waitFor("running on the second link") { owner.status.value as? McuOwner.Status.Running }
        owner.stop()

        assertTrue(running.acked)
        assertTrue("opens=${opens.get()}", opens.get() >= 2)
    }
}
