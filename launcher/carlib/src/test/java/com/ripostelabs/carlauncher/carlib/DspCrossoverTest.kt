package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The DSP app's crossover `4F 14` (`model/SubWoofModel_Two.sendFilter`, :428-450) and surround
 * `4F 0F` (`model/SoundModel_Two.sendSound`, :33-50). Frames summed by hand:
 * ck = 0xFF - (len + body).
 */
class DspCrossoverTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** The default is the boot replay's `4F 14` block: LP 20000 Hz, HP 20 Hz, both Bessel. */
    @Test
    fun defaultCrossoverIsTheStraceBlock() {
        val expected = bytes(
            0x0D, 0x0A, 0x0D, 0x4F, 0x14, 0x4E, 0x20, 0x00, 0x14, 0x4E, 0x20, 0x00, 0x14, 0x00, 0x00, 0x8B, 0x00,
        )

        assertArrayEquals(expected, McuSetupProtocol.dspCrossover(DspSound.Crossover()))
    }

    /**
     * `value / 256`, `value % 256` per filter, front LP, front HP, rear LP, rear HP, then the
     * LP and HP slopes. 12000 = 2E E0, 80 = 00 50, 3000 = 0B B8, 250 = 00 FA, LP Butterworth.
     * Sum 0x38C, ck 0x73.
     */
    @Test
    fun crossoverIsBigEndianWithSlopes() {
        val crossover = DspSound.Crossover(
            frontLp = 12000,
            frontHp = 80,
            rearLp = 3000,
            rearHp = 250,
            lpSlope = DspSound.Slope.BUTTERWORTH,
            hpSlope = DspSound.Slope.BESSEL,
        )
        val expected = bytes(
            0x0D, 0x0A, 0x0D, 0x4F, 0x14, 0x2E, 0xE0, 0x00, 0x50, 0x0B, 0xB8, 0x00, 0xFA, 0x01, 0x00, 0x73, 0x00,
        )

        assertArrayEquals(expected, McuSetupProtocol.dspCrossover(crossover))
    }

    /** Low-pass 3000..20000 Hz, high-pass 20..250 Hz (SubWoofModel_Two.java:176-183, 300-305). */
    @Test
    fun crossoverClampsToTheSliders() {
        val wild = DspSound.Crossover(frontLp = 99999, frontHp = 1, rearLp = 10, rearHp = 900).clamped()

        assertEquals(DspSound.Crossover(frontLp = 20000, frontHp = 20, rearLp = 3000, rearHp = 250), wild)
    }

    /** The default is the boot replay's `4F 0F 00 00` block: off, no centre, music. */
    @Test
    fun defaultSurroundIsTheStraceBlock() {
        val expected = bytes(0x0D, 0x0A, 0x05, 0x4F, 0x0F, 0x00, 0x00, 0x9C, 0x00)

        assertArrayEquals(expected, McuSetupProtocol.dspSurround(DspSound.Surround()))
    }

    /** `SOUND_OFFON + SOUND_ZHONGZHI * 2`, then the mode: on, centre, LCRS = 03 04. Sum 0x6A, ck 0x95. */
    @Test
    fun surroundPacksOnAndCentre() {
        val surround = DspSound.Surround(on = true, centre = true, mode = DspSound.SurroundMode.LCRS)
        val expected = bytes(0x0D, 0x0A, 0x05, 0x4F, 0x0F, 0x03, 0x04, 0x95, 0x00)

        assertArrayEquals(expected, McuSetupProtocol.dspSurround(surround))
        assertEquals(0x02, McuSetupProtocol.dspSurround(DspSound.Surround(centre = true))[5].toInt())
    }

    /** SoundFragment_Two.java:163-200: music 0, cinema 1, special 2, mono 3, LCRS 4. */
    @Test
    fun surroundModesAreStockNumbers() {
        assertEquals(listOf(0, 1, 2, 3, 4), DspSound.SurroundMode.values().map { it.wire })
    }

    /** Boot and wake carry the saved crossover and surround in the stock slots (DspService :64-83). */
    @Test
    fun bootBlocksCarryCrossoverAndSurround() {
        val crossover = DspSound.Crossover(frontHp = 100)
        val surround = DspSound.Surround(on = true, mode = DspSound.SurroundMode.CINEMA)
        val blocks = McuOwnerProtocol.vendorInit(McuSetup(dspCrossover = crossover, dspSurround = surround))

        assertArrayEquals(McuSetupProtocol.dspCrossover(crossover), blocks[2])
        assertArrayEquals(McuSetupProtocol.dspSurround(surround), blocks[7])
    }

    @Test
    fun rowsKeepCrossoverAndSurround() {
        val setup = McuSetup(
            dspCrossover = DspSound.Crossover(4000, 40, 5000, 60, DspSound.Slope.BUTTERWORTH, DspSound.Slope.BUTTERWORTH),
            dspSurround = DspSound.Surround(on = true, centre = true, mode = DspSound.SurroundMode.MONO),
        )

        assertEquals(setup, McuSetup.fromRows(setup.toRows()))
        assertEquals(McuSetup(), McuSetup.fromRows(emptyMap()))
    }
}
