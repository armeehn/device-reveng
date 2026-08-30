package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.RadioPreset
import kotlinx.coroutines.delay

/**
 * Preset ↔ tuner band logic shared by [RadioScreen] and [RadioCard].
 *
 * A preset stores the band it was saved on, but `sendUserFreq()` tunes within the *current*
 * band — the freq units differ between AM (kHz) and FM (10 kHz units), so replaying an AM
 * value while on FM mistunes. Recall therefore switches band first, and the active-slot
 * highlight compares band as well as freq (AM 8750 must not light while tuned FM 87.5).
 */
internal object RadioTuning {

    /** Band toggling cycles a small set (AM/FM1/FM2/…); more toggles than this is a loop. */
    const val MAX_BAND_TOGGLES = 4

    /** How long one band toggle gets to land before the band is re-polled. */
    const val BAND_SETTLE_MS = 300L

    /** AM and FM freq encodings are incompatible; FM1 vs FM2 at the same freq is the same tune. */
    fun sameBandClass(a: Int, b: Int): Boolean =
        CarService.isAmBand(a) == CarService.isAmBand(b)

    /** True when [preset] is the station the tuner is on — freq AND band class. */
    fun presetMatches(preset: RadioPreset, tunerBand: Int, tunerFreq: Int): Boolean =
        preset.freq == tunerFreq && sameBandClass(preset.band, tunerBand)

    /**
     * Recall [preset]: toggle the band until its class matches (bounded retries, re-polled
     * after each toggle), then tune. If the band never matches — or stops being readable
     * mid-switch — nothing is sent: replaying the freq into the wrong band mistunes, which
     * is worse than a recall that visibly did nothing.
     *
     * An *initially* unreadable band keeps the old behaviour and tunes blind: that is a
     * tuner that answers `sendUserFreq` but not `getRadioBand`, not a wrong band.
     *
     * Blocking AIDL calls — run on Dispatchers.IO. [settle] is injectable for tests.
     */
    suspend fun recallPreset(
        readBand: () -> Int?,
        toggleBand: () -> Unit,
        tune: (Int) -> Unit,
        preset: RadioPreset,
        settle: suspend () -> Unit = { delay(BAND_SETTLE_MS) },
    ) {
        var band = readBand()
        if (band == null) {
            tune(preset.freq)
            return
        }

        var toggles = 0
        while (!sameBandClass(band!!, preset.band)) {
            if (toggles >= MAX_BAND_TOGGLES) {
                return
            }
            toggleBand()
            toggles++
            settle()
            band = readBand() ?: return
        }

        tune(preset.freq)
    }
}
