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
    fun startupIsPowerOnVersionSetupVendorBlocksBacklight() {
        val frames = McuOwnerProtocol.startup(McuOwnerProtocol.StartupConfig(rds = true, radioZone = 2, backlightDay = 100, backlightNight = 60))

        val blocks = McuOwnerProtocol.vendorInit()
        assertEquals(5 + blocks.size, frames.size)
        assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.POWER_ON), frames[0])
        assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.MCU_VERSION), frames[1])
        // sendSetup(0, rds ? 0 : 1): RDS on is a ZERO.
        assertArrayEquals(McuOwnerProtocol.setup(0, 0), frames[2])
        assertArrayEquals(McuOwnerProtocol.setup(1, 2), frames[3])
        for (i in blocks.indices) {
            assertArrayEquals(blocks[i], frames[4 + i])
        }
        assertArrayEquals(McuOwnerProtocol.backlight(100, 60), frames.last())
    }

    /**
     * The config blocks byte for byte as eventcenter wrote them to /dev/ttyHS1 on a restart
     * (strace, unit on 0.1, 2026-09-18): sendFactorySet and friends, opcode 4F with a sub-id,
     * then BT state 1. Header, LEN, CK and trailer come from McuSerial.encode and must match.
     */
    @Test
    fun vendorInitBlocksMatchTheStraceCapture() {
        val wire = listOf(
            "0d0a334f10" + "0a".repeat(48) + "8d00",
            "0d0a044f0e009e00",
            "0d0a0d4f144e2000144e20001400008b00",
            "0d0a084f15fa0c0000008d00",
            "0d0a084f1200000000009600",
            "0d0a094f130000000000009400",
            "0d0a064f160000009400",
            "0d0a054f0f00009c00",
            "0d0a030b01f000",
        )
        val frames = McuOwnerProtocol.vendorInit()

        assertEquals(wire.size, frames.size)
        for (i in wire.indices) {
            assertEquals("frame $i", wire[i], frames[i].joinToString("") { "%02x".format(it) })
        }
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

    // ---- 72 / 74 keys ------------------------------------------------------------------------

    /** LEN 04 + 72 + 01 + 00 = 0x77, ~0x77 = 0x88: the panel POWER key as the reader frames it. */
    @Test
    fun panelKeyFromHandSummedFrame() {
        val events = McuSerial.Reader().feed(bytes(0x0D, 0x0A, 0x04, 0x72, 0x01, 0x00, 0x88, 0x00))

        val command = events.single() as McuSerial.Command
        assertEquals(McuOwnerProtocol.PanelKey(McuOwnerProtocol.Key.POWER, 0), McuOwnerProtocol.panelKey(command))
    }

    /** A custom panel key carries its press state in byte 2 (onMcuToPanelCustomKey). */
    @Test
    fun panelKeyKeepsByteTwoAsStatus() {
        assertEquals(McuOwnerProtocol.PanelKey(164, 1), McuOwnerProtocol.panelKey(command(0x72, 0xA4, 0x01)))
        assertEquals(McuOwnerProtocol.PanelKey(McuOwnerProtocol.Key.NEXT, 0), McuOwnerProtocol.panelKey(command(0x72, 0x02)))
        assertNull(McuOwnerProtocol.panelKey(command(0x72)))
        assertNull(McuOwnerProtocol.panelKey(command(0x74, 0x02, 0x01)))
    }

    /** LEN 06 + 74 + 02 + 01 + 00 + 5A = 0xD7, ~0xD7 = 0x28: slot 2 pressed at 90 (0x5A). */
    @Test
    fun wheelKeyFromHandSummedFrame() {
        val events = McuSerial.Reader().feed(bytes(0x0D, 0x0A, 0x06, 0x74, 0x02, 0x01, 0x00, 0x5A, 0x28, 0x00))

        val command = events.single() as McuSerial.Command
        assertEquals(McuOwnerProtocol.WheelKey(slot = 2, down = true, voltage = 0x5A), McuOwnerProtocol.wheelKey(command))
    }

    /** Byte 2 zero is the release (WPARAM 4); any other value is the press (WPARAM 3). */
    @Test
    fun wheelKeyStateAndBounds() {
        assertEquals(McuOwnerProtocol.WheelKey(0, down = false, voltage = 0), McuOwnerProtocol.wheelKey(command(0x74, 0x00, 0x00)))
        assertEquals(McuOwnerProtocol.WheelKey(9, down = true, voltage = 0xFF), McuOwnerProtocol.wheelKey(command(0x74, 0x09, 0x7F, 0x00, 0xFF)))
        // Slot 10 and the sign-passing 0xFF the vendor lets through are both refused.
        assertNull(McuOwnerProtocol.wheelKey(command(0x74, 0x0A, 0x01, 0x00, 0x10)))
        assertNull(McuOwnerProtocol.wheelKey(command(0x74, 0xFF, 0x01, 0x00, 0x10)))
        assertNull(McuOwnerProtocol.wheelKey(command(0x74, 0x01)))
        assertNull(McuOwnerProtocol.wheelKey(command(0x72, 0x01, 0x01)))
    }

    /** `08 xx`: LEN 03 + 08 + 00 = 0x0B → F4; + 01 = 0x0C → F3; + 0C = 0x17 → E8. */
    @Test
    fun systemKeyFrames() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x08, 0x00, 0xF4, 0x00), McuOwnerProtocol.systemKey(McuOwnerProtocol.SystemKey.VOLUME_UP))
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x08, 0x01, 0xF3, 0x00), McuOwnerProtocol.systemKey(McuOwnerProtocol.SystemKey.VOLUME_DOWN))
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x08, 0x0C, 0xE8, 0x00), McuOwnerProtocol.systemKey(McuOwnerProtocol.SystemKey.MUTE))
    }

    /** onCmdKeyEvent cases 17/18/19 → sendSystemKey(12/0/1); nothing else is echoed. */
    @Test
    fun onlyVolumeAndMuteAreEchoed() {
        assertEquals(McuOwnerProtocol.SystemKey.VOLUME_UP, McuOwnerProtocol.panelSystemKey(McuOwnerProtocol.Key.VOLUME_UP))
        assertEquals(McuOwnerProtocol.SystemKey.VOLUME_DOWN, McuOwnerProtocol.panelSystemKey(McuOwnerProtocol.Key.VOLUME_DOWN))
        assertEquals(McuOwnerProtocol.SystemKey.MUTE, McuOwnerProtocol.panelSystemKey(McuOwnerProtocol.Key.MUTE))
        assertNull(McuOwnerProtocol.panelSystemKey(McuOwnerProtocol.Key.POWER))
        assertNull(McuOwnerProtocol.panelSystemKey(McuOwnerProtocol.Key.NEXT))
        assertNull(McuOwnerProtocol.panelSystemKey(0xEE))
    }

    /** The reverse-camera allow list (onCmdKeyEvent, :2406): audio and track keys pass, the rest wait. */
    @Test
    fun reverseKeepsAudioAndTrackKeysOnly() {
        assertTrue(McuOwnerProtocol.panelKeyPassesReverse(McuOwnerProtocol.Key.VOLUME_UP))
        assertTrue(McuOwnerProtocol.panelKeyPassesReverse(McuOwnerProtocol.Key.MUTE))
        assertTrue(McuOwnerProtocol.panelKeyPassesReverse(McuOwnerProtocol.Key.NEXT))
        assertTrue(McuOwnerProtocol.panelKeyPassesReverse(McuOwnerProtocol.Key.PREV))
        assertFalse(McuOwnerProtocol.panelKeyPassesReverse(McuOwnerProtocol.Key.POWER))
        assertFalse(McuOwnerProtocol.panelKeyPassesReverse(McuOwnerProtocol.Key.MENU))
        assertFalse(McuOwnerProtocol.panelKeyPassesReverse(McuOwnerProtocol.Key.MODE))
    }

    /** LEN 03 + 0B + 00 = 0x0E, ~0x0E = 0xF1: what ACC off sends before the port closes. */
    @Test
    fun btStateZeroFrame() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x0B, 0x00, 0xF1, 0x00), McuOwnerProtocol.btState(McuOwnerProtocol.BT_DISCONNECTED))
    }

    /** reloadParam's modes and backlight, then POWERON, MCU_VERSION and the resumed mode again. */
    @Test
    fun reloadIsModesBacklightModesThenLastMode() {
        val frames = McuOwnerProtocol.reload(McuOwnerProtocol.StartupConfig(), McuOwnerProtocol.Mode.MUSIC)

        assertEquals(6, frames.size)
        assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.POWER_ON), frames[0])
        assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.MCU_VERSION), frames[1])
        assertArrayEquals(McuOwnerProtocol.backlight(100, 60), frames[2])
        assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.POWER_ON), frames[3])
        assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.MCU_VERSION), frames[4])
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x01, 0x0B, 0xF0, 0x00), frames[5])
    }

    @Test
    fun reloadWithoutAModeResumesNone() {
        val frames = McuOwnerProtocol.reload(McuOwnerProtocol.StartupConfig(), null)

        assertArrayEquals(McuOwnerProtocol.mode(McuOwnerProtocol.Mode.NONE), frames.last())
    }

    @Test
    fun wakeIsSleepStateOne() {
        assertTrue(McuOwnerProtocol.isWake(command(0x96, 0x01)))
        assertFalse(McuOwnerProtocol.isWake(command(0x96, 0x00)))
        assertFalse(McuOwnerProtocol.isWake(command(0x96)))
        assertFalse(McuOwnerProtocol.isWake(command(0x70, 0x01)))
    }

    // ---- 73 RADIO_EVENT: wire bytes summed by hand, read through the real reader ----------------

    /** Frame the bytes as the MCU would send them; a wrong CK surfaces as a missing Command. */
    private fun wire(vararg frame: Int): McuOwnerProtocol.RadioEvent? {
        val events = McuSerial.Reader().feed(bytes(*frame))
        val command = events.filterIsInstance<McuSerial.Command>().singleOrNull()
            ?: throw AssertionError("hand-summed frame did not parse: $events")
        return McuOwnerProtocol.radioEvent(command)
    }

    /** 96.30 MHz is 9630 = 0x259E in FM's 10 kHz units. 05+73+03+25+9E = 0x13E → ~3E = C1. */
    @Test
    fun fmFrequencyIsBigEndianTenKilohertz() {
        assertEquals(McuOwnerProtocol.RadioEvent.Frequency(9630), wire(0x0D, 0x0A, 0x05, 0x73, 0x03, 0x25, 0x9E, 0xC1, 0x00))
    }

    /** 1010 kHz is 1010 = 0x03F2 in AM's kHz units, same layout. 05+73+03+03+F2 = 0x170 → ~70 = 8F. */
    @Test
    fun amFrequencyIsBigEndianKilohertz() {
        assertEquals(McuOwnerProtocol.RadioEvent.Frequency(1010), wire(0x0D, 0x0A, 0x05, 0x73, 0x03, 0x03, 0xF2, 0x8F, 0x00))
    }

    /** Sub 1: band 3 (AM1), preset slot 2. 05+73+01+03+02 = 0x7E → ~7E = 81. */
    @Test
    fun bandCarriesBandAndPreset() {
        assertEquals(McuOwnerProtocol.RadioEvent.Band(band = 3, preset = 2), wire(0x0D, 0x0A, 0x05, 0x73, 0x01, 0x03, 0x02, 0x81, 0x00))
    }

    /** Sub 7 is the same handler; band 9 is out of the 0..6 range and dropped, the slot kept. 05+73+07+09+05 = 0x8D → ~8D = 72. */
    @Test
    fun bandAltDropsOutOfRangeBandKeepsPreset() {
        assertEquals(McuOwnerProtocol.RadioEvent.Band(band = null, preset = 5), wire(0x0D, 0x0A, 0x05, 0x73, 0x07, 0x09, 0x05, 0x72, 0x00))
        assertNull(McuOwnerProtocol.radioEvent(command(0x73, 0x01, 0x09, 0x06)))
    }

    /** Sub 2: slot 4. 04+73+02+04 = 0x7D → ~7D = 82. Slot 6 is past the six presets. */
    @Test
    fun presetIsOneSlotByte() {
        assertEquals(McuOwnerProtocol.RadioEvent.Preset(4), wire(0x0D, 0x0A, 0x04, 0x73, 0x02, 0x04, 0x82, 0x00))
        assertNull(McuOwnerProtocol.radioEvent(command(0x73, 0x02, 0x06)))
    }

    /** Sub 0: icons 0x03 = stereo + TP; flags 0x19 = RDS + TA + ST/mono. 05+73+00+03+19 = 0x94 → ~94 = 6B. */
    @Test
    fun stateSplitsIconsAndFlags() {
        val state = wire(0x0D, 0x0A, 0x05, 0x73, 0x00, 0x03, 0x19, 0x6B, 0x00) as McuOwnerProtocol.RadioEvent.State

        assertTrue(state.stereoIcon)
        assertTrue(state.tpIcon)
        assertFalse(state.traffic)
        assertFalse(state.noPty)
        assertTrue(state.rds)
        assertTrue(state.ta)
        assertTrue(state.stMono)
        assertFalse(state.pty)
        assertFalse(state.af)
        assertFalse(state.loc)
        assertFalse(state.ams)
        assertFalse(state.aps)
    }

    /** Sub 5: PTY 10. 04+73+05+0A = 0x86 → ~86 = 79. */
    @Test
    fun ptyIsOneByte() {
        assertEquals(McuOwnerProtocol.RadioEvent.Pty(10), wire(0x0D, 0x0A, 0x04, 0x73, 0x05, 0x0A, 0x79, 0x00))
    }

    /**
     * Sub 6: "CBC R1  " padded to RDS's eight. LEN 0B: 0B+73+06+43+42+43+20+52+31+20+20 = 0x22F → ~2F = D0.
     * The vendor's `new String(bArr, 2, length - 3)` keeps the padding; we trim it, NULs too.
     */
    @Test
    fun stationNameTrimsTrailingPadding() {
        assertEquals(
            McuOwnerProtocol.RadioEvent.StationName("CBC R1"),
            wire(0x0D, 0x0A, 0x0B, 0x73, 0x06, 0x43, 0x42, 0x43, 0x20, 0x52, 0x31, 0x20, 0x20, 0xD0, 0x00),
        )
        assertEquals(
            McuOwnerProtocol.RadioEvent.StationName("KEXP"),
            McuOwnerProtocol.radioEvent(command(0x73, 0x06, 0x4B, 0x45, 0x58, 0x50, 0x00, 0x00, 0x00, 0x00)),
        )
        assertNull(McuOwnerProtocol.radioEvent(command(0x73, 0x06)))
    }

    /** Sub 4 and 8: slot 2 holds 96.30. 06+73+04+02+25+9E = 0x142 → ~42 = BD. Slot 42 is past the list. */
    @Test
    fun freqListIsSlotThenBigEndian() {
        assertEquals(McuOwnerProtocol.RadioEvent.FreqList(2, 9630), wire(0x0D, 0x0A, 0x06, 0x73, 0x04, 0x02, 0x25, 0x9E, 0xBD, 0x00))
        assertEquals(McuOwnerProtocol.RadioEvent.FreqList(2, 9630), McuOwnerProtocol.radioEvent(command(0x73, 0x08, 0x02, 0x25, 0x9E)))
        assertNull(McuOwnerProtocol.radioEvent(command(0x73, 0x04, 0x2A, 0x25, 0x9E)))
    }

    @Test
    fun radioEventRejectsShortOtherAndUnknown() {
        assertNull(McuOwnerProtocol.radioEvent(command(0x73, 0x03, 0x25)))
        assertNull(McuOwnerProtocol.radioEvent(command(0x73)))
        assertNull(McuOwnerProtocol.radioEvent(command(0x72, 0x03, 0x25, 0x9E)))
        assertNull(McuOwnerProtocol.radioEvent(command(0x73, 0x09, 0x01)))
    }
    /** `83 yy MM dd HH mm ss`, year from 2000 (onCmdSysRTCTimeEvt, EventService.java:3041-3058). */
    @Test
    fun rtcTimeDecodesTheMcuClock() {
        val cmd = McuSerial.Command(McuOpcode.SYS_RTC_TIME.code, bytes(26, 9, 19, 14, 5, 7))
        assertEquals(LocalDateTime.of(2026, 9, 19, 14, 5, 7), McuOwnerProtocol.rtcTime(cmd))
    }

    /** The vendor ignores a year at or below 2018 (an unset RTC), a short body and other opcodes. */
    @Test
    fun rtcTimeRejectsWhatTheVendorIgnores() {
        assertNull(McuOwnerProtocol.rtcTime(McuSerial.Command(McuOpcode.SYS_RTC_TIME.code, bytes(18, 1, 1, 0, 0, 0))))
        assertNull(McuOwnerProtocol.rtcTime(McuSerial.Command(McuOpcode.SYS_RTC_TIME.code, bytes(26, 9, 19, 14, 5))))
        assertNull(McuOwnerProtocol.rtcTime(McuSerial.Command(McuOpcode.SYS_RTC_TIME.code, bytes(26, 13, 1, 0, 0, 0))))
        assertNull(McuOwnerProtocol.rtcTime(McuSerial.Command(McuOpcode.MUTE.code, bytes(26, 9, 19, 14, 5, 7))))
    }

    // 0.2 in the car: nothing restored the amp at boot, so the MCU never sent a 79 and the
    // Quick controls slider stayed "unavailable" (2026-09-22). The vendor restores it at boot.
    @Test
    fun startupRestoresTheMainVolumeWhenOneIsKnown() {
        val frames = McuOwnerProtocol.startup(McuOwnerProtocol.StartupConfig(mainVolume = 12))

        assertArrayEquals(McuOwnerProtocol.mainVolume(12), frames.last())
    }

    @Test
    fun startupLeavesTheVolumeAloneWhenNoneIsKnown() {
        val config = McuOwnerProtocol.StartupConfig()
        val frames = McuOwnerProtocol.startup(config)

        assertArrayEquals(McuOwnerProtocol.backlight(config.backlightDay, config.backlightNight), frames.last())
    }

    /**
     * canbus2's box startup (HiworldCanParseToyota.java:1325-1333), inner frames `5A A5 payload CK`
     * with CK = payload sum less one (SendUtil.java:60-85), each sent as outer `0D` behind `08`.
     * Hand sums: 03+6A+05+01+11 = 0x84, less one 0x83; +82 → 0xF4; +F0 → 0x62; 02+24+21+01 → 0x47.
     */
    @Test
    fun canBoxInitIsStockQueriesThenCarType() {
        val inner = listOf(
            bytes(0x5A, 0xA5, 0x03, 0x6A, 0x05, 0x01, 0x11, 0x83),
            bytes(0x5A, 0xA5, 0x03, 0x6A, 0x05, 0x01, 0x82, 0xF4),
            bytes(0x5A, 0xA5, 0x03, 0x6A, 0x05, 0x01, 0xF0, 0x62),
            bytes(0x5A, 0xA5, 0x02, 0x24, 0x21, 0x01, 0x47),
        )
        val expected = inner.map { McuSerial.encode(0x0D, bytes(0x08) + it) }

        val actual = McuOwnerProtocol.canBoxInit(CarProfiles.DEFAULT)

        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { i, frame -> assertArrayEquals("frame $i", frame, actual[i]) }
        assertArrayEquals(expected.last(), McuOwnerProtocol.canBoxCarType(CarProfiles.DEFAULT))
    }

    /** One whole wire frame by hand: LEN 0B, outer CK ~(0B+0D+08+5A+A5+03+6A+05+01+11+83) = ~0x26 = D9. */
    @Test
    fun canBoxQueryWireFrame() {
        val wire = bytes(0x0D, 0x0A, 0x0B, 0x0D, 0x08, 0x5A, 0xA5, 0x03, 0x6A, 0x05, 0x01, 0x11, 0x83, 0xD9, 0x00)

        assertArrayEquals(wire, McuOwnerProtocol.canBoxInit(CarProfiles.DEFAULT).first())
    }
}
