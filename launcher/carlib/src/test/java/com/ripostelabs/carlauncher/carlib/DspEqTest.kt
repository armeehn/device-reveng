package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DSP app's EQ frames (EqModel_Two_48.java): `4F 10` + 48 bytes of gain + 10 (sendEq,
 * :200-207) and `4F 11 band gain+10` (sendEq(int), :162-164). The writer is eventcenter's
 * `SendThread.sendData`, which the DSP broadcast reaches unchanged (EvtModel.java:934-939).
 */
class DspEqTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** The flat frame is the boot replay's first block, byte for byte (2026-09-18 strace). */
    @Test
    fun flatEqIsTheStraceBlock() {
        val expected = bytes(0x0D, 0x0A, 0x33, 0x4F, 0x10, *IntArray(DspEq.BANDS) { 0x0A }, 0x8D, 0x00)

        assertArrayEquals(expected, McuSetupProtocol.dspEq(DspEq.FLAT))
    }

    /** ROCK_TWO_48 (Constants.java:95), each band + 10, summed by hand. */
    @Test
    fun rockEqIsGainPlusTen() {
        val expected = bytes(
            0x0D, 0x0A, 0x33, 0x4F, 0x10,
            0x0D, 0x0D, 0x0E, 0x0E, 0x0F, 0x0F, 0x0F, 0x0F, 0x0C, 0x0C, 0x08, 0x08,
            0x06, 0x06, 0x06, 0x06, 0x07, 0x07, 0x0B, 0x0B, 0x0D, 0x0D, 0x0E, 0x0E,
            0x0F, 0x0F, 0x0F, 0x0F, 0x0E, 0x0E, 0x0D, 0x06, 0x06, 0x07, 0x07, 0x0B,
            0x0B, 0x0D, 0x0D, 0x0E, 0x0E, 0x0F, 0x0F, 0x0F, 0x0F, 0x0E, 0x0E, 0x0D,
            0x31, 0x00,
        )

        assertArrayEquals(expected, McuSetupProtocol.dspEq(DspEq.Preset.ROCK.curve!!))
    }

    /** One band while dragging: band 3 at -4 dB. 05+4F+11+03+06 = 0x6E. */
    @Test
    fun oneBandIsFourFEleven() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x05, 0x4F, 0x11, 0x03, 0x06, 0x91, 0x00), McuSetupProtocol.dspEqBand(3, -4))
    }

    @Test
    fun gainsClampToTheSlider() {
        val loud = McuSetupProtocol.dspEq(List(DspEq.BANDS) { 99 })

        assertEquals(20, loud[5].toInt())
        assertEquals(0, McuSetupProtocol.dspEqBand(0, -99)[6].toInt())
    }

    /** Every stock curve has 48 bands in range; a transcription slip would read as flat. */
    @Test
    fun presetsAreFullCurves() {
        for (preset in DspEq.Preset.values()) {
            val curve = preset.curve ?: continue

            assertEquals(preset.name, DspEq.BANDS, curve.size)
            if (preset != DspEq.Preset.FLAT) {
                assertNotEquals(preset.name, DspEq.FLAT, curve)
            }
        }
        assertEquals(DspEq.BANDS, DspEq.FREQUENCIES.size)
    }

    /** Stock's comma row: short or broken reads as flat (initEQ, :36-63). */
    @Test
    fun rowRoundTripsAndBadRowsReadFlat() {
        val rock = DspEq.Preset.ROCK.curve!!

        assertEquals(rock, DspEq.parse(DspEq.row(rock)))
        assertEquals(DspEq.FLAT, DspEq.parse("1, 2, 3"))
        assertEquals(DspEq.FLAT, DspEq.parse(null))
        assertEquals(DspEq.FLAT, DspEq.parse(DspEq.row(rock).replaceFirst("3", "x")))
    }

    @Test
    fun customSlotsMapToStockIndices() {
        assertEquals(9, DspEq.Preset.custom(0).index)
        assertEquals(11, DspEq.Preset.custom(2).index)
        assertEquals(DspEq.Preset.FLAT, DspEq.Preset.of(5))
    }

    /** The saved sound rides the boot burst in place of the flat defaults (DspService :64-83). */
    @Test
    fun bootBlocksCarryTheSavedSound() {
        val rock = DspEq.Preset.ROCK.curve!!
        val blocks = McuOwnerProtocol.vendorInit(McuSetup(dspEq = rock, dspLoud = true))

        assertArrayEquals(McuSetupProtocol.dspEq(rock), blocks[0])
        assertArrayEquals(McuSetupProtocol.dspLoud(true), blocks[1])
    }

    @Test
    fun rowsKeepTheCurvePresetAndSlots() {
        val setup = McuSetup(
            dspEq = DspEq.Preset.JAZZ.curve!!,
            dspPreset = DspEq.Preset.JAZZ.index,
            dspCustom = listOf(DspEq.Preset.ROCK.curve!!, DspEq.FLAT, DspEq.Preset.POP.curve!!),
        )

        assertEquals(setup, McuSetup.fromRows(setup.toRows()))
        assertEquals(DspEq.Preset.FLAT.index, McuSetup().dspPreset)
    }

    /** setMode (:180-188): a preset loads its curve; a custom slot loads what was saved there. */
    @Test
    fun presetLoadsItsCurve() {
        val saved = DspEq.Preset.VOCAL.curve!!
        val base = McuSetup(dspCustom = listOf(DspEq.FLAT, saved, DspEq.FLAT))

        assertEquals(DspEq.Preset.ROCK.curve, base.withDspPreset(DspEq.Preset.ROCK).dspEq)
        assertEquals(saved, base.withDspPreset(DspEq.Preset.CUSTOM_2).dspEq)
        assertEquals(DspEq.Preset.CUSTOM_2.index, base.withDspPreset(DspEq.Preset.CUSTOM_2).dspPreset)
    }

    /** setEqValue (:78-101): one band moves and the preset becomes NULL. */
    @Test
    fun bandEditLeavesThePreset() {
        val edited = McuSetup().withDspPreset(DspEq.Preset.ROCK).withDspBand(2, 15)

        assertEquals(DspEq.EDITED, edited.dspPreset)
        assertEquals(DspEq.GAIN_MAX, edited.dspEq[2])
        assertEquals(DspEq.Preset.ROCK.curve!![3], edited.dspEq[3])
    }

    /** saveCustomerEqValues (:103-120): the curve goes into the slot and the slot is selected. */
    @Test
    fun saveGoesToTheSlot() {
        val edited = McuSetup().withDspBand(0, 4)
        val saved = edited.withDspCustomSaved(2)

        assertEquals(edited.dspEq, saved.dspCustom[2])
        assertEquals(DspEq.Preset.CUSTOM_3.index, saved.dspPreset)
    }

    /** The EQ page's default button (EqFragment_two_48.java:546-551): flat, loudness off. */
    @Test
    fun resetIsFlatAndQuiet() {
        val custom = listOf(DspEq.Preset.ROCK.curve!!, DspEq.FLAT, DspEq.FLAT)
        val reset = McuSetup(dspEq = DspEq.Preset.ROCK.curve!!, dspLoud = true, dspCustom = custom).dspReset()

        assertEquals(DspEq.FLAT, reset.dspEq)
        assertEquals(DspEq.Preset.FLAT.index, reset.dspPreset)
        assertEquals(false, reset.dspLoud)
        assertEquals(custom, reset.dspCustom)
    }

    /** A wake re-sends the sound as it is now, not as it was when the launcher started. */
    @Test
    fun reloadReadsTheSetupAtSendTime() {
        var setup = McuSetup()
        val config = McuOwnerProtocol.StartupConfig(setup = setup, setupSource = { setup })
        setup = setup.withDspPreset(DspEq.Preset.JAZZ)

        val frames = McuOwnerProtocol.reload(config, null)

        assertTrue(frames.any { it.contentEquals(McuSetupProtocol.dspEq(DspEq.Preset.JAZZ.curve!!)) })
    }
}
