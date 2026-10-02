package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call plays through the DSP as speech and the saved sound comes back after it, byte for
 * byte the blocks boot would send.
 */
class CallSoundTest {

    private val sent = mutableListOf<List<Byte>>()

    /** A music setup with everything a call must not keep: rock EQ, loudness, boost, surround. */
    private val music = McuSetup().copy(
        dspEq = DspEq.Preset.ROCK.curve!!,
        dspLoud = true,
        dspBass = DspSound.Bass(level = 8, freq = 3),
        dspSub = DspSound.Sub(gain = 20),
        dspSurround = DspSound.Surround(on = true, mode = DspSound.SurroundMode.CINEMA),
        dspField = DspSound.Field().withDelay(DspSound.Speaker.LEFT_FRONT, 40),
    )
    private var current: McuSetup? = music
    private val sound = CallSound({ sent += it.toList() }, { current })

    private fun frames(blocks: List<ByteArray>) = blocks.map { it.toList() }

    @Test
    fun callStartSendsSpeechAndEndRestoresTheSavedSound() {
        sound.onCall(true)
        assertEquals(frames(CallSound.speech(music)), sent)

        sent.clear()
        sound.onCall(false)
        assertEquals(frames(CallSound.saved(music)), sent)
    }

    @Test
    fun onlyEdgesSend() {
        sound.onCall(false)
        assertTrue(sent.isEmpty())
        sound.onCall(true)
        sound.onCall(true)
        assertEquals(8, sent.size)
    }

    /** The restore reads the store when the call ends: an edit made mid-call is what returns. */
    @Test
    fun restoreUsesTheSetupAtTheEnd() {
        sound.onCall(true)
        current = music.copy(dspEq = DspEq.Preset.JAZZ.curve!!)
        sent.clear()
        sound.onCall(false)
        assertEquals(frames(CallSound.saved(current)), sent)
    }

    /** Boot's blocks and the restore are one list, so they cannot drift apart. */
    @Test
    fun savedIsWhatBootSends() {
        val boot = McuOwnerProtocol.vendorInit(music).map { it.toList() }
        assertEquals(frames(CallSound.saved(music)), boot.take(8))
    }

    @Test
    fun speechIsFlatWithPresenceAndFrontOnly() {
        val speech = frames(CallSound.speech(music))
        assertEquals(McuSetupProtocol.dspEq(CallSound.SPEECH_EQ).toList(), speech[0])
        assertEquals(McuSetupProtocol.dspLoud(false).toList(), speech[1])
        assertEquals(McuSetupProtocol.dspCrossover(DspSound.Crossover(frontHp = CallSound.SPEECH_HP_HZ)).toList(), speech[2])
        assertEquals(McuSetupProtocol.dspBass(DspSound.Bass()).toList(), speech[6])
        assertEquals(McuSetupProtocol.dspSurround(DspSound.Surround()).toList(), speech[7])

        val gains = DspSound.Field().gains.toMutableList().also {
            it[DspSound.Speaker.LEFT_REAR.ordinal] = 0
            it[DspSound.Speaker.RIGHT_REAR.ordinal] = 0
        }
        assertEquals(McuSetupProtocol.dspChannelGain(music.dspField.copy(gains = gains)).toList(), speech[5])
        // Delays are the seat, not the music: kept.
        assertEquals(McuSetupProtocol.dspDelay(music.dspField).toList(), speech[4])
    }

    @Test
    fun presenceIsMild() {
        assertTrue(CallSound.SPEECH_EQ.all { it in 0..3 })
        assertEquals(13, CallSound.SPEECH_EQ.sum())
    }
}
