package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DSP app's sound blocks beyond the EQ, byte for byte against its own encoders
 * (`com.choiceway.dsp`, paths under `model/`). Expected frames are summed by hand:
 * `0D 0A len body ck 00`, len = body + 1, ck = 0xFF - (len + body).
 */
class DspSoundTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ── Subwoofer, 4F 15 (SubWoofModel_Two.sendStrongBass, :155-169) ─────────────────────────

    /** The default is the boot replay's `4F 15` block (2026-09-18 strace): 250 Hz, gain 12. */
    @Test
    fun defaultSubIsTheStraceBlock() {
        val expected = bytes(0x0D, 0x0A, 0x08, 0x4F, 0x15, 0xFA, 0x0C, 0x00, 0x00, 0x00, 0x8D, 0x00)

        assertArrayEquals(expected, McuSetupProtocol.dspSub(DspSound.Sub()))
    }

    /**
     * `{79, 21, freq, gain, PHASE, AMPLIFIER, OFF_ON}`: 80 Hz, gain 18 (+6 dB), phase
     * reversed, amplifier on, flag 1. 08+4F+15+50+12+01+01+01 = 0xD1, ck 0x2E.
     */
    @Test
    fun subCarriesEveryField() {
        val sub = DspSound.Sub(freq = 80, gain = 18, reversePhase = true, amplifier = true, offOn = true)
        val expected = bytes(0x0D, 0x0A, 0x08, 0x4F, 0x15, 0x50, 0x12, 0x01, 0x01, 0x01, 0x2E, 0x00)

        assertArrayEquals(expected, McuSetupProtocol.dspSub(sub))
    }

    /** The sliders' ranges (:51-58): cut-off 20..250 Hz, gain 0..24. */
    @Test
    fun subClampsToTheSliders() {
        val high = McuSetupProtocol.dspSub(DspSound.Sub(freq = 300, gain = 40))
        val low = McuSetupProtocol.dspSub(DspSound.Sub(freq = 5, gain = -3))

        assertEquals(0xFA, high[5].toInt() and 0xFF)
        assertEquals(24, high[6].toInt())
        assertEquals(20, low[5].toInt())
        assertEquals(0, low[6].toInt())
    }

    // ── Bass boost, 4F 16 (BassModel_Two.sendFrequency, :126-140) ────────────────────────────

    /** The default is the boot replay's `4F 16 00 00 00` block. */
    @Test
    fun defaultBassIsTheStraceBlock() {
        val expected = bytes(0x0D, 0x0A, 0x06, 0x4F, 0x16, 0x00, 0x00, 0x00, 0x94, 0x00)

        assertArrayEquals(expected, McuSetupProtocol.dspBass(DspSound.Bass()))
    }

    /** `{79, 22, 0, BASS_PROGRESS, FREQUENCY}`: level 6, "≤ 80" (index 7). Sum 0x78, ck 0x87. */
    @Test
    fun bassIsZeroLevelFrequency() {
        val expected = bytes(0x0D, 0x0A, 0x06, 0x4F, 0x16, 0x00, 0x06, 0x07, 0x87, 0x00)

        assertArrayEquals(expected, McuSetupProtocol.dspBass(DspSound.Bass(level = 6, freq = 7)))
    }

    /** Level 0..12 (fragment_bass_two.xml max), frequency one of PickerView_Two's 13 entries. */
    @Test
    fun bassClampsToTheControls() {
        val frame = McuSetupProtocol.dspBass(DspSound.Bass(level = 20, freq = 99))

        assertEquals(DspSound.BASS_LEVEL_MAX, frame[6].toInt())
        assertEquals(DspSound.BASS_FREQS.size - 1, frame[7].toInt())
        assertEquals(13, DspSound.BASS_FREQS.size)
    }

    // ── Saved and re-sent ────────────────────────────────────────────────────────────────────

    /** Boot and wake carry the saved sub and bass in the stock slots (DspService :64-83). */
    @Test
    fun bootBlocksCarrySubAndBass() {
        val sub = DspSound.Sub(freq = 100, gain = 20, reversePhase = true)
        val bass = DspSound.Bass(level = 4, freq = 3)
        val blocks = McuOwnerProtocol.vendorInit(McuSetup(dspSub = sub, dspBass = bass))

        assertArrayEquals(McuSetupProtocol.dspSub(sub), blocks[3])
        assertArrayEquals(McuSetupProtocol.dspBass(bass), blocks[6])
    }

    @Test
    fun wakeCarriesTheSubAsItIsNow() {
        var setup = McuSetup()
        val config = McuOwnerProtocol.StartupConfig(setup = setup, setupSource = { setup })
        setup = setup.copy(dspSub = DspSound.Sub(freq = 60))

        val frames = McuOwnerProtocol.reload(config, null)

        assertTrue(frames.any { it.contentEquals(McuSetupProtocol.dspSub(DspSound.Sub(freq = 60))) })
    }

    @Test
    fun rowsKeepSubAndBass() {
        val setup = McuSetup(
            dspSub = DspSound.Sub(freq = 120, gain = 3, reversePhase = true, amplifier = true, offOn = true),
            dspBass = DspSound.Bass(level = 9, freq = 11),
        )

        assertEquals(setup, McuSetup.fromRows(setup.toRows()))
    }

    /** Stock wrote floats, "gain|freq" (saveStrongBassValues, :108-113); those rows still read. */
    @Test
    fun stockFloatRowsRead() {
        val rows = mapOf(
            McuSetup.KEY_DSP_SUB to "18.0|63.0",
            McuSetup.KEY_DSP_BASS_LEVEL to "5.0",
            McuSetup.KEY_DSP_PHASE to "1",
        )
        val setup = McuSetup.fromRows(rows)

        assertEquals(DspSound.Sub(freq = 63, gain = 18, reversePhase = true), setup.dspSub)
        assertEquals(5, setup.dspBass.level)
        assertEquals(DspSound.Sub(), McuSetup.fromRows(mapOf(McuSetup.KEY_DSP_SUB to "x|")).dspSub)
    }
}
