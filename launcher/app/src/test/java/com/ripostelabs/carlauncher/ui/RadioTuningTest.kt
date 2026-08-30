package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.data.RadioPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preset recall must land on the preset's band before tuning: `sendUserFreq` tunes within the
 * current band, and AM/FM freq units are incompatible, so an AM preset replayed on FM mistunes
 * (that was the bug). The active-slot highlight has the same trap in reverse — freq-only compare
 * lit AM 8750 while tuned to FM 87.5.
 *
 * Band values follow [com.ripostelabs.carlauncher.carlib.CarService.isAmBand]: >= 3 is AM.
 */
class RadioTuningTest {

    private val fm = 0
    private val fm2 = 1
    private val am = 3

    private class FakeTuner(bands: List<Int?>) {
        private val sequence = bands.toMutableList()
        var band: Int? = sequence.removeAt(0)
        var toggles = 0
        var tuned: Int? = null

        fun read(): Int? = band
        fun toggle() {
            toggles++
            if (sequence.isNotEmpty()) band = sequence.removeAt(0)
        }
        fun tune(freq: Int) { tuned = freq }
    }

    private fun recall(tuner: FakeTuner, preset: RadioPreset) = runBlocking {
        RadioTuning.recallPreset(
            readBand = tuner::read,
            toggleBand = tuner::toggle,
            tune = tuner::tune,
            preset = preset,
            settle = {},
        )
    }

    @Test
    fun matchingBandTunesWithoutToggling() {
        val tuner = FakeTuner(listOf(fm))

        recall(tuner, RadioPreset(band = fm, freq = 8750))

        assertEquals(0, tuner.toggles)
        assertEquals(8750, tuner.tuned)
    }

    @Test
    fun fm2CountsAsFmClassNoToggle() {
        val tuner = FakeTuner(listOf(fm2))

        recall(tuner, RadioPreset(band = fm, freq = 8750))

        assertEquals(0, tuner.toggles)
        assertEquals(8750, tuner.tuned)
    }

    @Test
    fun amPresetOnFmTogglesUntilAmThenTunes() {
        // The cycle passes through a second FM band before reaching AM.
        val tuner = FakeTuner(listOf(fm, fm2, am))

        recall(tuner, RadioPreset(band = am, freq = 1010))

        assertEquals(2, tuner.toggles)
        assertEquals(1010, tuner.tuned)
    }

    @Test
    fun bandThatNeverMatchesNeverTunes() {
        // A tuner whose toggle never reaches AM: bounded retries, then give up silently —
        // tuning anyway would replay AM kHz into the FM band.
        val tuner = FakeTuner(listOf(fm, fm, fm, fm, fm, fm, fm, fm))

        recall(tuner, RadioPreset(band = am, freq = 1010))

        assertEquals(RadioTuning.MAX_BAND_TOGGLES, tuner.toggles)
        assertEquals(null, tuner.tuned)
    }

    @Test
    fun initiallyUnreadableBandTunesBlind() {
        // No band answer at all: keep the old behaviour rather than a dead preset button.
        val tuner = FakeTuner(listOf(null))

        recall(tuner, RadioPreset(band = am, freq = 1010))

        assertEquals(0, tuner.toggles)
        assertEquals(1010, tuner.tuned)
    }

    @Test
    fun bandUnreadableMidSwitchAborts() {
        val tuner = FakeTuner(listOf(fm, null))

        recall(tuner, RadioPreset(band = am, freq = 1010))

        assertEquals(1, tuner.toggles)
        assertEquals(null, tuner.tuned)
    }

    @Test
    fun highlightComparesBandClassAndFreq() {
        val amPreset = RadioPreset(band = am, freq = 8750)
        val fmPreset = RadioPreset(band = fm, freq = 8750)

        // Tuned FM 87.5 (raw 8750): the AM preset with the same raw value must NOT light.
        assertFalse(RadioTuning.presetMatches(amPreset, tunerBand = fm, tunerFreq = 8750))
        assertTrue(RadioTuning.presetMatches(fmPreset, tunerBand = fm, tunerFreq = 8750))
        // An FM2 tuner report still matches an FM-saved preset at the same freq.
        assertTrue(RadioTuning.presetMatches(fmPreset, tunerBand = fm2, tunerFreq = 8750))
        assertFalse(RadioTuning.presetMatches(fmPreset, tunerBand = fm, tunerFreq = 9010))
    }
}
