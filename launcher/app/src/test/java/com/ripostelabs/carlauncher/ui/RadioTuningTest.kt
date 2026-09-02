package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.data.RadioPreset
import com.ripostelabs.carlauncher.ui.RadioTuning.BandClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preset recall must land on the preset's band before tuning: AM/FM freq units are
 * incompatible, so an AM preset replayed on FM mistunes (that was the bug). The active-slot
 * highlight has the same trap in reverse — freq-only compare lit AM 8750 while tuned FM 87.5.
 *
 * Band values follow [com.ripostelabs.carlauncher.carlib.CarService.isAmBand]: >= 3 is AM.
 */
class RadioTuningTest {

    private val fm = 0
    private val fm2 = 1
    private val am = 3

    /** A tuner whose band reads follow [bands] one per select; the first is the initial read. */
    private class FakeTuner(bands: List<Int?>) {
        private val sequence = bands.toMutableList()
        var band: Int? = sequence.removeAt(0)
        val selected = mutableListOf<BandClass>()
        var tuned: Int? = null

        fun read(): Int? = band
        fun select(target: BandClass) {
            selected += target
            if (sequence.isNotEmpty()) band = sequence.removeAt(0)
        }
        fun tune(freq: Int) { tuned = freq }
    }

    private fun recall(tuner: FakeTuner, preset: RadioPreset) = runBlocking {
        RadioTuning.recallPreset(
            readBand = tuner::read,
            selectBand = tuner::select,
            tune = tuner::tune,
            preset = preset,
            settle = {},
        )
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
    fun matchingBandTunesWithoutSelecting() {
        val tuner = FakeTuner(listOf(fm))

        recall(tuner, RadioPreset(band = fm, freq = 8750))

        assertEquals(emptyList<BandClass>(), tuner.selected)
        assertEquals(8750, tuner.tuned)
    }

    @Test
    fun fm2CountsAsFmClassNoSelect() {
        val tuner = FakeTuner(listOf(fm2))

        recall(tuner, RadioPreset(band = fm, freq = 8750))

        assertEquals(emptyList<BandClass>(), tuner.selected)
        assertEquals(8750, tuner.tuned)
    }

    @Test
    fun amPresetOnFmSendsTheAmKeyThenTunes() {
        val tuner = FakeTuner(listOf(fm, am))

        recall(tuner, RadioPreset(band = am, freq = 1010))

        assertEquals(listOf(BandClass.AM), tuner.selected)
        assertEquals(1010, tuner.tuned)
    }

    @Test
    fun slowTunerGetsTheKeyAgainUntilItReports() {
        // The MCU answers the band getter from its last frame; one settle may not be enough.
        val tuner = FakeTuner(listOf(fm, fm, am))

        assertTrue(switch(tuner, BandClass.AM))

        assertEquals(listOf(BandClass.AM, BandClass.AM), tuner.selected)
    }

    @Test
    fun bandThatNeverLandsNeverTunes() {
        // A tuner that never reports AM: bounded attempts, then give up silently —
        // tuning anyway would replay AM kHz into the FM band.
        val tuner = FakeTuner(listOf(fm, fm, fm, fm, fm, fm, fm, fm))

        recall(tuner, RadioPreset(band = am, freq = 1010))

        assertEquals(RadioTuning.MAX_BAND_ATTEMPTS, tuner.selected.size)
        assertEquals(null, tuner.tuned)
    }

    @Test
    fun initiallyUnreadableBandTunesBlind() {
        // No band answer at all: keep the old behaviour rather than a dead preset button.
        val tuner = FakeTuner(listOf(null))

        recall(tuner, RadioPreset(band = am, freq = 1010))

        assertEquals(emptyList<BandClass>(), tuner.selected)
        assertEquals(1010, tuner.tuned)
    }

    @Test
    fun bandUnreadableMidSwitchAborts() {
        val tuner = FakeTuner(listOf(fm, null))

        recall(tuner, RadioPreset(band = am, freq = 1010))

        assertEquals(listOf(BandClass.AM), tuner.selected)
        assertEquals(null, tuner.tuned)
    }

    @Test
    fun otherBandClassFlipsTheClass() {
        assertEquals(BandClass.AM, RadioTuning.otherBandClass(fm))
        assertEquals(BandClass.AM, RadioTuning.otherBandClass(fm2))
        assertEquals(BandClass.FM, RadioTuning.otherBandClass(am))
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
