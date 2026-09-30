package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner path behind CarService's audio calls: each setter sends one clamped frame and
 * caches it, each report folds into the cache. Frames are `0D 0A LEN body CK 00`,
 * CK = ~(LEN + Σbody), summed by hand.
 */
class OwnerAudioTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val sent = mutableListOf<ByteArray>()
    private val audio = OwnerAudio(send = { sent += it })

    @Test
    fun emptyUntilReportedOrSent() {
        assertEquals(AudioState(), audio.state.value)
        assertTrue(sent.isEmpty())
    }

    /** 03+09+05 = 0x11, ~ = 0xEE; 03+09+00 = 0x0C, ~ = 0xF3. */
    @Test
    fun eqModeClampsToSixPresets() {
        audio.setEqMode(9)
        audio.setEqMode(-1)

        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x09, 0x05, 0xEE, 0x00), sent[0])
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x09, 0x00, 0xF3, 0x00), sent[1])
        assertEquals(0, audio.state.value.eqMode)
    }

    /** DSP domain 0..20 (EasyFieldFragment_two_2.java:180). 04+2F+14+00 = 0x47, ~ = 0xB8. */
    @Test
    fun balanceFaderClampsToAmpDomain() {
        audio.setBalanceFader(25, -3)

        assertArrayEquals(bytes(0x0D, 0x0A, 0x04, 0x2F, 0x14, 0x00, 0xB8, 0x00), sent.single())
        assertEquals(20, audio.state.value.balance)
        assertEquals(0, audio.state.value.fader)
    }

    /** 03+15+14 = 0x2C, ~ = 0xD3. */
    @Test
    fun subwooferClampsToTwenty() {
        audio.setSubwoofer(30)

        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x15, 0x14, 0xD3, 0x00), sent.single())
        assertEquals(20, audio.state.value.subwoofer)
    }

    @Test
    fun beepIsBareSix() {
        audio.beep()

        assertArrayEquals(bytes(0x0D, 0x0A, 0x02, 0x06, 0xF7, 0x00), sent.single())
        assertEquals(AudioState(), audio.state.value)
    }

    /** Reports overwrite what was sent: a panel EQ key moves the MCU first. */
    @Test
    fun reportsFoldIntoState() {
        audio.setEqMode(1)
        audio.onAudio(McuSetupProtocol.AudioReport.Eq(3))
        audio.onAudio(McuSetupProtocol.AudioReport.BalanceFader(6, 8))
        audio.onAudio(McuSetupProtocol.AudioReport.Loudness(true))
        audio.onAudio(McuSetupProtocol.AudioReport.Tone(McuSetup.Tone(bass = 9, mid = 7, treble = 5)))

        val expected = AudioState(
            eqMode = 3,
            balance = 6,
            fader = 8,
            loudness = true,
            tone = McuSetup.Tone(bass = 9, mid = 7, treble = 5),
        )
        assertEquals(expected, audio.state.value)
        assertEquals(1, sent.size)
    }
}
