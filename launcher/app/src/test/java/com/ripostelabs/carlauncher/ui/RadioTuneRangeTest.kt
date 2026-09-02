package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.ui.RadioTuning.BandClass
import com.ripostelabs.carlauncher.ui.RadioTuning.TuneRange
import kotlinx.coroutines.runBlocking
import com.ripostelabs.carlauncher.data.RadioPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tune slider spans the dial in whatever raw unit the tuner happens to report, and snaps
 * to the dial step on release. The raw unit is GUESSED, so every encoding [formatFreqLabel]
 * accepts must produce a range that agrees with it — a slider in 10 kHz units over a tuner
 * reporting kHz would send 87.5 MHz as 8.75 MHz.
 *
 * Band values follow [com.ripostelabs.carlauncher.carlib.CarService.isAmBand]: >= 3 is AM.
 */
class RadioTuneRangeTest {

    private val fm = 0
    private val fm2 = 1
    private val am = 3

    @Test
    fun fmDialIsTenKilohertzUnits() {
        // The vendor formats getRadioFreq() as "%d.%02d MHZ": 9630 is 96.30 MHz.
        assertEquals(TuneRange(8750, 10790, 10), RadioTuning.tuneRange(fm, 9630))
        assertEquals("96.3 MHz", formatFreqLabel(fm, 9630))
    }

    @Test
    fun amDialIsKilohertz() {
        assertEquals(TuneRange(530, 1710, 10), RadioTuning.tuneRange(am, 1010))
        assertEquals("1010 kHz", formatFreqLabel(am, 1010))
    }

    @Test
    fun rangeEndsRenderAsTheDialLimits() {
        val range = RadioTuning.tuneRange(fm, 9630)
        assertEquals("87.5 MHz", formatFreqLabel(fm, range.min))
        assertEquals("107.9 MHz", formatFreqLabel(fm, range.max))
    }

    @Test
    fun unknownFreqStillGivesADial() {
        // Before the first poll the tuner reports 0; the slider needs a non-empty span.
        val range = RadioTuning.tuneRange(fm, 0)

        assertTrue(range.span > 0)
        assertEquals(8750, range.min)
    }

    @Test
    fun vendorFavouriteDecodesFreqAndBandFlag() {
        // Rdo_MyFavoriteN = freq | (am ? 0x10000 : 0), as the vendor radio writes it.
        assertEquals(RadioPreset(band = fm, freq = 9630), RadioTuning.decodeVendorFavorite("9630"))
        assertEquals(RadioPreset(band = am, freq = 1010), RadioTuning.decodeVendorFavorite("66546"))
        assertEquals(null, RadioTuning.decodeVendorFavorite("0"))
        assertEquals(null, RadioTuning.decodeVendorFavorite(""))
        assertEquals(null, RadioTuning.decodeVendorFavorite("FM_96.30"))
    }

    @Test
    fun vendorFavouriteRoundTrips() {
        for (preset in listOf(RadioPreset(fm, 9630), RadioPreset(fm2, 8750), RadioPreset(am, 1710))) {
            val back = RadioTuning.decodeVendorFavorite(RadioTuning.encodeVendorFavorite(preset))!!
            assertEquals(preset.freq, back.freq)
            assertTrue(RadioTuning.sameBandClass(preset.band, back.band))
        }
    }

    @Test
    fun outOfDialReadingWidensTheRange() {
        // A tuner on 76.0 MHz (Japan band) must not be drawn as 87.5.
        assertEquals(7600, RadioTuning.tuneRange(fm, 7600).min)
        assertEquals(10900, RadioTuning.tuneRange(fm, 10900).max)
    }

    @Test
    fun snapRoundsToTheStep() {
        val range = TuneRange(8750, 10800, 10)

        assertEquals(10150, RadioTuning.snap(range, 10154f))
        assertEquals(10160, RadioTuning.snap(range, 10155f))
        assertEquals(8750, RadioTuning.snap(range, 8751f))
    }

    @Test
    fun snapClampsToTheDial() {
        val range = TuneRange(530, 1710, 10)

        assertEquals(530, RadioTuning.snap(range, 100f))
        assertEquals(1710, RadioTuning.snap(range, 9999f))
    }

    @Test
    fun bandClassSplitsAtThree() {
        assertEquals(BandClass.FM, RadioTuning.bandClassOf(fm))
        assertEquals(BandClass.FM, RadioTuning.bandClassOf(fm2))
        assertEquals(BandClass.AM, RadioTuning.bandClassOf(am))
    }

    private class FakeTuner(bands: List<Int?>) {
        private val sequence = bands.toMutableList()
        var band: Int? = sequence.removeAt(0)
        var sent = 0

        fun read(): Int? = band
        fun select(@Suppress("UNUSED_PARAMETER") target: BandClass) {
            sent++
            if (sequence.isNotEmpty()) band = sequence.removeAt(0)
        }
    }

    private fun switch(tuner: FakeTuner, target: BandClass) = runBlocking {
        RadioTuning.switchBandClass(
            readBand = tuner::read,
            selectBand = tuner::select,
            target = target,
            settle = {},
        )
    }

    @Test
    fun selectingTheActiveClassSendsNothing() {
        val tuner = FakeTuner(listOf(fm2))

        assertTrue(switch(tuner, BandClass.FM))
        assertEquals(0, tuner.sent)
    }

    @Test
    fun selectingAmLandsInOneKey() {
        val tuner = FakeTuner(listOf(fm, am))

        assertTrue(switch(tuner, BandClass.AM))
        assertEquals(1, tuner.sent)
    }

    @Test
    fun keyThatNeverLandsGivesUp() {
        val tuner = FakeTuner(listOf(fm, fm, fm, fm, fm, fm, fm))

        assertFalse(switch(tuner, BandClass.AM))
        assertEquals(RadioTuning.MAX_BAND_ATTEMPTS, tuner.sent)
    }

    @Test
    fun unreadableBandFails() {
        assertFalse(switch(FakeTuner(listOf(null)), BandClass.AM))
        assertFalse(switch(FakeTuner(listOf(fm, null)), BandClass.AM))
    }
}
