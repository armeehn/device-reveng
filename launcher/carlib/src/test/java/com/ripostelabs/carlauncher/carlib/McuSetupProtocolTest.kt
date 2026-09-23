package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors are the vendor writer (`SendThread.sendData`, EventService.java:10650) applied to the
 * bodies each `send*` builds, summed by hand: `0D 0A LEN body CK 00`, CK = ~(LEN + Σbody).
 */
class McuSetupProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun command(opcode: Int, vararg payload: Int) = McuSerial.Command(opcode, bytes(*payload))

    /** sendBalFadValue (:9440): `2F bal fad`, amp domain 0..14, centre 7. 04+2F+07+07 = 0x41. */
    @Test
    fun balanceFaderIsTwoF() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x04, 0x2F, 0x07, 0x07, 0xBE, 0x00), McuSetupProtocol.balanceFader(7, 7))
    }

    /** sendEQMode (:4291): `09 mode`. 03+09+03 = 0x0F. */
    @Test
    fun eqModeIsNine() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x09, 0x03, 0xF0, 0x00), McuSetupProtocol.eqMode(3))
    }

    /** sendAudioValue (:9420): `22 bass mid treble bassF midF trebleF`. 08+22+15 = 0x3F. */
    @Test
    fun toneIsTwoTwo() {
        assertArrayEquals(
            bytes(0x0D, 0x0A, 0x08, 0x22, 0x07, 0x07, 0x07, 0x00, 0x00, 0x00, 0xC0, 0x00),
            McuSetupProtocol.tone(McuSetup.Tone(bass = 7, mid = 7, treble = 7)),
        )
    }

    /** sendSndSWVol (:9541): `15 level`. 03+15+0A = 0x22. */
    @Test
    fun subwooferIsOneFive() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x15, 0x0A, 0xDD, 0x00), McuSetupProtocol.subwoofer(10))
    }

    /** sendGPSVol (:9532): `26 level`. 03+26+1C = 0x45. */
    @Test
    fun navVolumeIsTwoSix() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x26, 0x1C, 0xBA, 0x00), McuSetupProtocol.navVolume(28))
    }

    /** Loudness has no setter: the SYS_LOUD system key (EventUtils.java:1790) toggles it, `08 0D`. */
    @Test
    fun loudnessToggleIsSystemKeyThirteen() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x08, 0x0D, 0xE7, 0x00), McuSetupProtocol.loudnessToggle())
    }

    /** beep (:4308): the bare `06`. 02+06 = 0x08. */
    @Test
    fun beepIsSix() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x02, 0x06, 0xF7, 0x00), McuSetupProtocol.beep())
    }

    /** sendSetup(2, !enable) (:9925): the byte is ONE when the beep is OFF. */
    @Test
    fun keyBeepSetupIsInverted() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x04, 0x05, 0x02, 0x00, 0xF4, 0x00), McuSetupProtocol.keyBeep(McuSetup.Beep.ON))
        assertArrayEquals(bytes(0x0D, 0x0A, 0x04, 0x05, 0x02, 0x01, 0xF3, 0x00), McuSetupProtocol.keyBeep(McuSetup.Beep.OFF))
    }

    /** sendSleepTime (:9361): `49 05 hi lo` with 1/2/3 -> 960/1440/2880 and anything else 480. */
    @Test
    fun sleepTimeTable() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x05, 0x49, 0x05, 0x03, 0xC0, 0xE9, 0x00), McuSetupProtocol.sleepTime(1))
        assertArrayEquals(bytes(0x0D, 0x0A, 0x05, 0x49, 0x05, 0x05, 0xA0, 0x07, 0x00), McuSetupProtocol.sleepTime(2))
        assertArrayEquals(bytes(0x0D, 0x0A, 0x05, 0x49, 0x05, 0x0B, 0x40, 0x61, 0x00), McuSetupProtocol.sleepTime(3))
        assertArrayEquals(bytes(0x0D, 0x0A, 0x05, 0x49, 0x05, 0x01, 0xE0, 0xCB, 0x00), McuSetupProtocol.sleepTime(0))
    }

    /** sendVolumeGain (:9661): `49 08` then ten gains, radio first, other last. 0D+49+08+10×1C = 0x176. */
    @Test
    fun sourceGainsAreRadioFirst() {
        val gains = McuSetup.SourceGains(radio = 1, music = 2, movie = 3, btCall = 4, btMusic = 5, tv = 6, dvd = 7, aux = 8, usb = 9, other = 10)
        assertArrayEquals(
            bytes(0x0D, 0x0A, 0x0D, 0x49, 0x08, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 0x6A, 0x00),
            McuSetupProtocol.sourceGains(gains),
        )
        assertArrayEquals(
            bytes(0x0D, 0x0A, 0x0D, 0x49, 0x08, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x89, 0x00),
            McuSetupProtocol.sourceGains(McuSetup.SourceGains()),
        )
    }

    /** sendCarDefaultHostVol (:3161): `4F 05 level`. 04+4F+05+1E = 0x76. */
    @Test
    fun hostDefaultVolumeIsFourFSubFive() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x04, 0x4F, 0x05, 0x1E, 0x89, 0x00), McuSetupProtocol.hostDefaultVolume(30))
    }

    /** sendDspLoud (:15050): `4F 0E state`. 04+4F+0E+01 = 0x62. */
    @Test
    fun dspLoudIsFourFSubFourteen() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x04, 0x4F, 0x0E, 0x01, 0x9D, 0x00), McuSetupProtocol.dspLoud(true))
    }

    /** sendAccDelayTime (:3169): `49 17 minutes seconds`. 05+49+17+01+1E = 0x84. */
    @Test
    fun accDelayIsMinutesSeconds() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x05, 0x49, 0x17, 0x01, 0x1E, 0x7B, 0x00), McuSetupProtocol.accDelay(90))
    }

    /** Bytes are clamped, never wrapped: a gain of 300 is 255 on the wire, not 44. */
    @Test
    fun valuesClampToAByte() {
        assertArrayEquals(McuSetupProtocol.subwoofer(0xFF), McuSetupProtocol.subwoofer(300))
        assertArrayEquals(McuSetupProtocol.subwoofer(0), McuSetupProtocol.subwoofer(-3))
    }

    /**
     * The boot set in the vendor's order (initSysEventState, :3794-3800): sendFactorySet's beep
     * setup, host default volume and nav volume, then EQ and the gains. The `0F` factory
     * bit-field, `49 17`, the `45` baud frame and the `10` block stay out: config-derived.
     */
    @Test
    fun bootIsTheVendorOrder() {
        val setup = McuSetup(eqMode = 2, sleepTime = 3, keyBeep = McuSetup.Beep.ON, navVolume = 12, hostDefaultVolume = 20)

        val frames = McuSetupProtocol.boot(setup)

        assertEquals(5, frames.size)
        assertArrayEquals(McuSetupProtocol.keyBeep(McuSetup.Beep.ON), frames[0])
        assertArrayEquals(McuSetupProtocol.hostDefaultVolume(20), frames[1])
        assertArrayEquals(McuSetupProtocol.navVolume(12), frames[2])
        assertArrayEquals(McuSetupProtocol.eqMode(2), frames[3])
        assertArrayEquals(McuSetupProtocol.sourceGains(McuSetup.SourceGains()), frames[4])
    }

    /** Boot sleep rides StartupConfig.sleepTime; the option maps to the same bytes sleepTime() sends. */
    @Test
    fun sleepOptionMatchesTheFrame() {
        for (option in 0..3) {
            assertArrayEquals(McuSetupProtocol.sleepTime(option), McuOwnerProtocol.sleepTime(McuSetupProtocol.sleepOption(option)))
        }
        assertEquals(McuOwnerProtocol.SleepTime.H8, McuSetupProtocol.sleepOption(9))
    }

    /** onCmdBalanceEvent (:2965): `7A bal fad`, needs both bytes. */
    @Test
    fun balanceReport() {
        assertEquals(McuSetupProtocol.Balance(7, 9), McuSetupProtocol.balance(command(0x7A, 7, 9)))
        assertNull(McuSetupProtocol.balance(command(0x7A, 7)))
        assertNull(McuSetupProtocol.balance(command(0x79, 7, 9)))
    }

    /** onCmdEQEvent (:2909): `77 mode`. */
    @Test
    fun eqReport() {
        assertEquals(4, McuSetupProtocol.eqMode(command(0x77, 4)))
        assertNull(McuSetupProtocol.eqMode(command(0x77)))
    }

    /** onCmdLoudnessEvent (:2981): `7B state`, any non-zero is on. */
    @Test
    fun loudnessReport() {
        assertTrue(McuSetupProtocol.loudness(command(0x7B, 1))!!)
        assertTrue(McuSetupProtocol.loudness(command(0x7B, 0x80))!!)
        assertFalse(McuSetupProtocol.loudness(command(0x7B, 0))!!)
        assertNull(McuSetupProtocol.loudness(command(0x7B)))
    }

    /** onCmdBMTVolEvent (:2880): `76 bass mid treble x`, needs four payload bytes. */
    @Test
    fun toneReport() {
        assertEquals(McuSetup.Tone(7, 8, 9), McuSetupProtocol.tone(command(0x76, 7, 8, 9, 0)))
        assertNull(McuSetupProtocol.tone(command(0x76, 7, 8, 9)))
    }
}
