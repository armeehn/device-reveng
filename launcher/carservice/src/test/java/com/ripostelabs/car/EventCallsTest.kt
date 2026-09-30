package com.ripostelabs.car

import com.ripostelabs.carlauncher.carlib.McuOwner
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import com.ripostelabs.carlauncher.carlib.McuSerial
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCallsTest {

    /** Records what reached the owner, so a refused call can be shown to have done nothing. */
    private class FakeLink(private val source: Int = 7) : Link {
        val sources = mutableListOf<Int>()
        val frames = mutableListOf<ByteArray>()
        override fun status(): McuOwner.Status = McuOwner.Status.Idle
        override fun open() = Unit
        override fun close() = Unit
        override fun setStartup(packed: ByteArray) = Unit
        override fun setSource(mode: Int): Boolean { sources += mode; return true }
        override fun currentSource() = source
        override fun selectCar(id: String) = Unit
        override fun send(frame: ByteArray) { frames += frame }
        override fun setPowerKey(mode: Int) = Unit
        override fun lastSource() = Link.NO_SOURCE
    }

    private class FakePower : Power {
        var reboots = 0
        override fun reboot() { reboots++ }
        override fun wipeData() = Unit
    }

    private val link = FakeLink()
    private val power = FakePower()
    private val notes = mutableListOf<String>()
    private var reversing = false

    private fun calls(vararg held: String, on: Link = link) =
        EventCalls(Gate { it in held }, on, power, { reversing }) { notes += it }

    private fun event(opcode: Int, vararg payload: Int) =
        McuEvent.of(McuSerial.Command(opcode, ByteArray(payload.size) { payload[it].toByte() }))

    @Test
    fun validModeIsTheCurrentSource() {
        assertEquals(7, calls().validMode())
    }

    @Test
    fun validModeBeforeAnySourceIsNone() {
        val none = calls(on = FakeLink(Link.NO_SOURCE))
        assertEquals(McuOwnerProtocol.Mode.NONE.code, none.validMode())
    }

    @Test
    fun backCarFollowsTheReverseLine() {
        val c = calls()
        assertFalse(c.backCar())

        reversing = true
        assertTrue(c.backCar())
    }

    @Test
    fun mcuVersionComesFromTheVersionAck() {
        val c = calls()
        assertEquals("", c.mcuVer())

        // 70 MODE_ACK for SRC_MCU_VERSION (80), then the ASCII version.
        c.observe(event(0x70, 80, 'V'.code, '1'.code))
        assertEquals("V1", c.mcuVer())
    }

    @Test
    fun muteAndVolumeFollowTheMcu() {
        val c = calls()
        c.observe(event(0x78, 1))
        c.observe(event(0x79, 12))

        assertTrue(c.muteOn())
        assertEquals(12, c.mainVolume())
    }

    @Test
    fun sendModeSetsTheSource() {
        calls(CONTROL_PERMISSION).sendMode(11)
        assertEquals(listOf(11), link.sources)
    }

    @Test
    fun radioKeyTuneMuteAndBacklightSendTheOwnerFrames() {
        val c = calls(CONTROL_PERMISSION)
        c.radioKey(3)
        c.userFreq(9130, fm = true)
        c.mute(true)
        c.backlight(5, 2)

        val expected = listOf(
            McuOwnerProtocol.radioKey(3),
            McuOwnerProtocol.userFreq(9130, true),
            McuOwnerProtocol.mute(true),
            McuOwnerProtocol.backlight(5, 2),
        )
        assertEquals(expected.size, link.frames.size)
        expected.zip(link.frames).forEach { (want, got) -> assertArrayEquals(want, got) }
    }

    @Test
    fun softwareRebootReboots() {
        calls(CONTROL_PERMISSION).reboot()
        assertEquals(1, power.reboots)
    }

    // A stock app holds neither permission: the change is dropped, noted once, never thrown.
    @Test
    fun changesWithoutControlDoNothingAndNoteOnce() {
        val c = calls(READ_PERMISSION)
        c.sendMode(11)
        c.sendMode(11)
        c.reboot()

        assertTrue(link.sources.isEmpty())
        assertEquals(0, power.reboots)
        assertEquals(2, notes.size)
    }

    @Test
    fun settingsAnswerTheCallersFallback() {
        val c = calls()
        assertEquals(4, c.setting("Sys_x", 4))
        assertEquals("a", c.setting("Sys_x", "a"))
        assertEquals(true, c.setting("Sys_x", true))
    }

    @Test
    fun unservedCallsAreNotedOncePerName() {
        val c = calls()
        c.unserved("getRadioFreq")
        c.unserved("getRadioFreq")
        c.unserved("getAirData")

        assertEquals(2, notes.size)
    }
}
