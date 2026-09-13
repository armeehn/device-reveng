package com.ripostelabs.carlauncher.carlib

import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors are the vendor writer's algorithm (`SendThread.sendData`, EventService.java:10650)
 * applied to the frames `initSysEventState` and `powerOff` send, summed by hand. No capture yet.
 */
class McuOwnerProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun command(opcode: Int, vararg payload: Int) = McuSerial.Command(opcode, bytes(*payload))

    /** LEN 03 + 01 + 64 = 0x68, ~0x68 = 0x97. SRC_POWERON is decimal 100 in the enum. */
    @Test
    fun powerOnModeIsHexSixtyFour() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x01, 0x64, 0x97, 0x00), McuOwnerProtocol.mode(McuOwnerProtocol.Mode.POWER_ON))
    }

    /** LEN 03 + 01 + 63 = 0x67, ~0x67 = 0x98. */
    @Test
    fun nullModeFrame() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x01, 0x63, 0x98, 0x00), McuOwnerProtocol.mode(McuOwnerProtocol.Mode.NULL))
    }

    /** 06 + 2E + 64 + 3C + 50 + C8 = 0x1EC → low byte EC, ~EC = 13. Fine bounds 80/200 are the vendor's constants. */
    @Test
    fun backlightCarriesFineBounds() {
        assertArrayEquals(
            bytes(0x0D, 0x0A, 0x06, 0x2E, 0x64, 0x3C, 0x50, 0xC8, 0x13, 0x00),
            McuOwnerProtocol.backlight(100, 60),
        )
    }

    @Test
    fun startupIsPowerOnVersionSetupBacklight() {
        val frames = McuOwnerProtocol.startup(McuOwnerProtocol.StartupConfig(rds = true, radioZone = 2, backlightDay = 100, backlightNight = 60))

        assertEquals(5, frames.size)
        assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.POWER_ON), frames[0])
        assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.MCU_VERSION), frames[1])
        // sendSetup(0, rds ? 0 : 1): RDS on is a ZERO.
        assertArrayEquals(McuOwnerProtocol.setup(0, 0), frames[2])
        assertArrayEquals(McuOwnerProtocol.setup(1, 2), frames[3])
        assertArrayEquals(McuOwnerProtocol.backlight(100, 60), frames[4])
    }

    /** `13 yy MM dd HH mm ss`, year from 2000; then SRC_POWEROFF (0x65) five times. */
    @Test
    fun powerOffStampsClockThenRepeatsPowerOff() {
        val frames = McuOwnerProtocol.powerOff(LocalDateTime.of(2026, 9, 13, 14, 5, 7))

        assertEquals(1 + McuOwnerProtocol.POWER_OFF_REPEATS, frames.size)
        assertArrayEquals(McuSerial.encode(0x13, bytes(26, 9, 13, 14, 5, 7)), frames[0])
        for (i in 1..McuOwnerProtocol.POWER_OFF_REPEATS) {
            assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.POWER_OFF), frames[i])
        }
    }

    @Test
    fun userFreqBandByteIsZeroForFm() {
        // 0x2706 = 9990 → 99.9 MHz as the vendor scales it.
        assertArrayEquals(McuSerial.encode(0x0C, bytes(0x27, 0x06, 0x00)), McuOwnerProtocol.userFreq(9990, fm = true))
        assertArrayEquals(McuSerial.encode(0x0C, bytes(0x02, 0x1E, 0x01)), McuOwnerProtocol.userFreq(542, fm = false))
    }

    @Test
    fun modeAckMatchesOnlyTheAwaitedMode() {
        val ack = command(0x70, 0x63)

        assertTrue(McuOwnerProtocol.isModeAck(ack, McuOwnerProtocol.Mode.NULL))
        assertFalse(McuOwnerProtocol.isModeAck(ack, McuOwnerProtocol.Mode.POWER_ON))
        assertFalse(McuOwnerProtocol.isModeAck(command(0x71, 0x63), McuOwnerProtocol.Mode.NULL))
        assertFalse(McuOwnerProtocol.isModeAck(command(0x70), McuOwnerProtocol.Mode.NULL))
    }

    /** 0x0B = illumination + reverse + ACC line, brake clear; 0x41 = start/stop + left turn. */
    @Test
    fun sysEventBits() {
        val e = McuOwnerProtocol.sysEvent(command(0x71, 0x0B, 0x41))!!

        assertTrue(e.illumination)
        assertTrue(e.reverse)
        assertTrue(e.accLine)
        assertFalse(e.brake)
        assertFalse(e.disc)
        assertFalse(e.usb)
        assertFalse(e.rightTurn)
        assertTrue(e.startStop)
        assertTrue(e.leftTurn)
        assertFalse(e.mcan)
        assertFalse(e.hdmi)
    }

    @Test
    fun sysEventNeedsTwoBytes() {
        assertNull(McuOwnerProtocol.sysEvent(command(0x71, 0x0B)))
        assertNull(McuOwnerProtocol.sysEvent(command(0x72, 0x0B, 0x41)))
    }

    /** Bit 7 flags a silent change; the level is the low seven bits (onCmdMainVolEvent). */
    @Test
    fun mainVolumeSilentBit() {
        assertEquals(McuOwnerProtocol.MainVolume(21, silent = true), McuOwnerProtocol.mainVolume(command(0x79, 0x95)))
        assertEquals(McuOwnerProtocol.MainVolume(21, silent = false), McuOwnerProtocol.mainVolume(command(0x79, 0x15)))
        assertNull(McuOwnerProtocol.mainVolume(command(0x79)))
    }

    /** `78`: low bits are the mute value, bit 7 the silent flag, same shape as volume. */
    @Test
    fun muteBits() {
        assertEquals(McuOwnerProtocol.Mute(muted = true, silent = false), McuOwnerProtocol.mute(command(0x78, 0x01)))
        assertEquals(McuOwnerProtocol.Mute(muted = false, silent = true), McuOwnerProtocol.mute(command(0x78, 0x80)))
        assertNull(McuOwnerProtocol.mute(command(0x79, 0x01)))
    }

    @Test
    fun keyIsFirstPayloadByte() {
        assertEquals(McuOwnerProtocol.Key.POWER, McuOwnerProtocol.key(command(0x72, 0x01, 0x00)))
        assertNull(McuOwnerProtocol.key(command(0x71, 0x01)))
    }
}
