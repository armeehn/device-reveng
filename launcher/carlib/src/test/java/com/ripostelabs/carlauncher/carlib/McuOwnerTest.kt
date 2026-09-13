package com.ripostelabs.carlauncher.carlib

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
        val radio = CopyOnWriteArrayList<McuOwnerProtocol.RadioEvent>()
        val signals = CopyOnWriteArrayList<CanSignal>()
        val other = CopyOnWriteArrayList<McuSerial.Command>()

        override fun onSysEvent(event: McuOwnerProtocol.SysEvent) { sys.add(event) }
        override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) { this.volume.add(volume) }
        override fun onKey(key: Int) { keys.add(key) }
        override fun onRadio(event: McuOwnerProtocol.RadioEvent) { radio.add(event) }
        override fun onCanSignal(signal: CanSignal, atMs: Long) { signals.add(signal) }
        override fun onOther(command: McuSerial.Command) { other.add(command) }
    }

    private fun owner(link: FakeLink, gate: Gate = Gate(eventcenter = false, enabled = true), recorder: Recorder = Recorder()) =
        McuOwner(gate, recorder, openLink = { link }, ackTimeoutMs = 20)

    private fun <T> waitFor(what: String, timeoutMs: Long = 2_000, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
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

        link.feed(McuSerial.encode(McuOpcode.SYS_EVENT.code, bytes(0x02, 0x00)))
        link.feed(McuSerial.encode(McuOpcode.MAIN_VOLUME.code, bytes(0x15)))
        link.feed(McuSerial.encode(McuOpcode.KEY_EVENT.code, bytes(McuOwnerProtocol.Key.POWER, 0x00)))
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
        assertEquals(McuOwnerProtocol.Key.POWER, recorder.keys[0])
        assertFalse("0x32 must decode to a real signal, not Unknown", recorder.signals[0] is CanSignal.Unknown)
        assertEquals(0x97, recorder.other[0].opcode)
        assertEquals(0L, counted.badChecksum)
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
}
