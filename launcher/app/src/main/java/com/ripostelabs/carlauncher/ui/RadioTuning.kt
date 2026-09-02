package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.RadioPreset
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Preset ↔ tuner band logic shared by [RadioScreen] and [RadioCard].
 *
 * A preset stores the band it was saved on, and the freq units differ between AM (kHz) and
 * FM (10 kHz units): `sendUserFreq()` carries a band flag, but the MCU still has to be *on*
 * that band for the tune to be audible, and the tuner reports the band it is really on.
 * Recall therefore switches band first, and the active-slot highlight compares band as well
 * as freq (AM 8750 must not light while tuned FM 87.5).
 */
internal object RadioTuning {

    /** How many times a band key is sent before a tuner that never reports it is given up on. */
    const val MAX_BAND_ATTEMPTS = 4

    /** How long one band key gets to land before the band is re-polled. */
    const val BAND_SETTLE_MS = 300L

    /**
     * The two band classes the AIDL exposes. The vendor radio app also showed a "NET" tab; that
     * is an internet-radio *source*, not a tuner band — `getRadioBand()` never reports it and
     * nothing in the 144-method `IEventService` selects it, so it has no place here.
     */
    enum class BandClass { AM, FM }

    /**
     * A tunable span in the tuner's own raw units.
     *
     *     min ──┬──┬──┬──┬── … ──┬── max        every tick is one [step]
     *          87.5 87.6 87.7           108.0     (FM, MHz)
     */
    data class TuneRange(val min: Int, val max: Int, val step: Int) {
        val span: Int get() = max - min
    }

    fun bandClassOf(band: Int): BandClass =
        if (CarService.isAmBand(band)) BandClass.AM else BandClass.FM

    /** The class a band button toggles *to* from the tuner's current band. */
    fun otherBandClass(band: Int): BandClass =
        if (CarService.isAmBand(band)) BandClass.FM else BandClass.AM

    /** Send the MCU's direct band key for [target] (no cycling: FM is 30, AM is 31). */
    fun selectBand(carService: CarService, target: BandClass) {
        when (target) {
            BandClass.AM -> carService.radioSelectAm()
            BandClass.FM -> carService.radioSelectFm()
        }
    }

    /** AM and FM freq encodings are incompatible; FM1 vs FM2 at the same freq is the same tune. */
    fun sameBandClass(a: Int, b: Int): Boolean =
        CarService.isAmBand(a) == CarService.isAmBand(b)

    /** True when [preset] is the station the tuner is on — freq AND band class. */
    fun presetMatches(preset: RadioPreset, tunerBand: Int, tunerFreq: Int): Boolean =
        preset.freq == tunerFreq && sameBandClass(preset.band, tunerBand)

    /**
     * The scrub range for [band], in the raw units the tuner is reporting [sampleFreq] in.
     *
     * The raw unit is GUESSED (CAR_API §3.2), so it is inferred from the magnitude of the live
     * reading with the same thresholds [formatFreqLabel] uses — one heuristic, not two that can
     * disagree. Limits are the North American dial (FM 87.5–108.0 MHz by 100 kHz, AM 530–1710 kHz
     * by 10 kHz). A reading outside them widens the range rather than being clamped: the slider
     * must never show a station the tuner is not on.
     */
    fun tuneRange(band: Int, sampleFreq: Int): TuneRange {
        val range = if (CarService.isAmBand(band)) {
            val unitsPerKhz = if (sampleFreq > RAW_HZ_THRESHOLD) 1000 else 1
            TuneRange(
                min = AM_MIN_KHZ * unitsPerKhz,
                max = AM_MAX_KHZ * unitsPerKhz,
                step = AM_STEP_KHZ * unitsPerKhz,
            )
        } else {
            val unitsPerMhz = when {
                sampleFreq > RAW_HZ_THRESHOLD -> 1000 // kHz
                sampleFreq > RAW_TEN_KHZ_THRESHOLD -> 100 // 10 kHz
                else -> 10 // 100 kHz
            }
            TuneRange(
                min = FM_MIN_100KHZ * unitsPerMhz / 10,
                max = FM_MAX_100KHZ * unitsPerMhz / 10,
                step = FM_STEP_100KHZ * unitsPerMhz / 10,
            )
        }

        if (sampleFreq <= 0) {
            return range
        }
        return range.copy(
            min = minOf(range.min, sampleFreq),
            max = maxOf(range.max, sampleFreq),
        )
    }

    /** Round a slider position to the nearest [TuneRange.step] tick, inside the range. */
    fun snap(range: TuneRange, raw: Float): Int {
        val ticks = ((raw - range.min) / range.step).roundToInt()
        return (range.min + ticks * range.step).coerceIn(range.min, range.max)
    }

    /**
     * Put the tuner on [target] and wait for it to say so: send the band key, settle, re-poll,
     * bounded. Returns true once the tuner reports the target class; false when the band is
     * unreadable or never lands. A tuner already on [target] gets no key at all.
     *
     * Blocking AIDL calls — run on Dispatchers.IO. [settle] is injectable for tests.
     */
    suspend fun switchBandClass(
        readBand: () -> Int?,
        selectBand: (BandClass) -> Unit,
        target: BandClass,
        settle: suspend () -> Unit = { delay(BAND_SETTLE_MS) },
    ): Boolean {
        var band = readBand() ?: return false

        var attempts = 0
        while (bandClassOf(band) != target) {
            if (attempts >= MAX_BAND_ATTEMPTS) {
                return false
            }
            selectBand(target)
            attempts++
            settle()
            band = readBand() ?: return false
        }

        return true
    }

    /**
     * Recall [preset]: land on its band class via [switchBandClass], then tune. If the band
     * never matches — or stops being readable mid-switch — nothing is sent: replaying the freq
     * into the wrong band mistunes, which is worse than a recall that visibly did nothing.
     *
     * An *initially* unreadable band keeps the old behaviour and tunes blind: that is a
     * tuner that answers `sendUserFreq` but not `getRadioBand`, not a wrong band.
     *
     * Blocking AIDL calls — run on Dispatchers.IO. [settle] is injectable for tests.
     */
    suspend fun recallPreset(
        readBand: () -> Int?,
        selectBand: (BandClass) -> Unit,
        tune: (Int) -> Unit,
        preset: RadioPreset,
        settle: suspend () -> Unit = { delay(BAND_SETTLE_MS) },
    ) {
        if (readBand() == null) {
            tune(preset.freq)
            return
        }

        val landed = switchBandClass(readBand, selectBand, bandClassOf(preset.band), settle)
        if (!landed) {
            return
        }

        tune(preset.freq)
    }

    /** Raw-unit thresholds shared with [formatFreqLabel]: above 30000 is Hz/kHz, above 3000 is 10 kHz. */
    private const val RAW_HZ_THRESHOLD = 30_000
    private const val RAW_TEN_KHZ_THRESHOLD = 3_000

    /** North American FM dial, in 100 kHz units. */
    private const val FM_MIN_100KHZ = 875
    private const val FM_MAX_100KHZ = 1080
    private const val FM_STEP_100KHZ = 1

    /** North American AM dial, in kHz. */
    private const val AM_MIN_KHZ = 530
    private const val AM_MAX_KHZ = 1710
    private const val AM_STEP_KHZ = 10
}
