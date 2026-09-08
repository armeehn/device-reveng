package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.DriveLog.Sample
import com.ripostelabs.carlauncher.carlib.DriveLog.Source

/**
 * SpeedCalibration — pairs the ECU's own speed with the candidate fields, as they arrive.
 *
 * [DriveLog] has known how to fit a scale since #61 and has never had a caller: nothing ever
 * built a [Sample]. This does. It is the point where the two buses meet for the one purpose the
 * raw tap was bought for — finding out what `0x17` and `0x13` actually mean in km/h.
 *
 *     MCU 0x17 ─┐                                  ┌─ raw017 ─┐
 *     MCU 0x13 ─┼─ onCandidate ─▶ latest raws ──▶  │ raw013   ├─ Sample ──▶ DriveLog fit
 *     OBD 0x0D ─┘  onReference ─▶ latest ref  ──▶  └─ refKmh ─┘
 *
 * ── Who creates a sample, and when ──────────────────────────────────────────────────────────────
 * A sample is minted when a CANDIDATE arrives, carrying the most recent reference. That matches
 * [DriveLog]'s design exactly: it guards the age of the reference, so the reference must be the
 * thing that can be old. The other candidate is attached only if it is as fresh as the reference
 * is allowed to be; a `0x17` from forty seconds ago is not evidence about this instant.
 *
 * ── What it refuses ─────────────────────────────────────────────────────────────────────────────
 * Nothing here ever produces a km/h for a screen. [fit] returns scales with the evidence behind
 * them — point count and how many 10 km/h bands were visited — and returns null with a reason
 * when that evidence is thin. The rule that no speed reaches the Vehicle tiles until one is
 * calibrated is enforced downstream by nobody reading a scale out of here until it is not null.
 */
class SpeedCalibration(private val maxSamples: Int = MAX_SAMPLES) {

    private data class Timed(val value: Int, val atMs: Long)
    private data class Ref(val kmh: Double, val atMs: Long)

    private var latest017: Timed? = null
    private var latest013: Timed? = null
    private var latestRef: Ref? = null

    private val samples = ArrayDeque<Sample>()

    /** Everything minted so far, oldest first. Unusable samples are kept, with their reason. */
    fun samples(): List<Sample> = samples.toList()

    fun sampleCount(): Int = samples.size

    /** The ECU answered. Stored, not sampled: the next candidate mints the sample. */
    fun onReference(kmh: Int, atMs: Long) {
        latestRef = Ref(kmh.toDouble(), atMs)
    }

    /**
     * A candidate arrived on the MCU path. `0x17` comes as [CanSignal.SpeedCandidate]; `0x13`
     * rides [CanSignal.TripInfo]. Anything else is not a speed and is ignored.
     */
    fun onCandidate(signal: CanSignal, atMs: Long) {
        when (signal) {
            is CanSignal.SpeedCandidate -> {
                if (signal.source != SOURCE_017) {
                    return
                }
                latest017 = Timed(signal.raw, atMs)
            }

            is CanSignal.TripInfo -> latest013 = Timed(signal.speedCandidateRaw, atMs)

            else -> return
        }

        mint(atMs)
    }

    /** What the data supports right now. */
    data class Fit(
        /** km/h per LSB for `0x17`, or null if `0x17` has too few usable points. */
        val scale017: Double?,
        /** km/h per LSB for `0x13`, or null likewise. */
        val scale013: Double?,
        val usable: Int,
        val bands: Int,
    )

    /**
     * Fit both scales, or explain why not.
     *
     * Returns null when the log as a whole cannot support a calibration: too few usable samples,
     * or every usable sample sits in one 10 km/h band. The last wrong answer this project shipped
     * rested on two points, and a line through one cluster means nothing.
     */
    fun fit(): Fit? {
        val all = samples()
        val usable = all.count(DriveLog::usableForCalibration)
        if (usable < DriveLog.MIN_POINTS) {
            return null
        }

        val bands = DriveLog.distinctSpeedBands(all)
        if (bands < MIN_BANDS) {
            return null
        }

        return Fit(
            scale017 = DriveLog.scaleKmhPerLsb(all) { it.raw017 },
            scale013 = DriveLog.scaleKmhPerLsb(all) { it.raw013 },
            usable = usable,
            bands = bands,
        )
    }

    /** One line per sample, CSV, with the header first. What gets pulled off the car. */
    fun csv(): String = (listOf(DriveLog.HEADER) + samples().map(DriveLog::row)).joinToString("\n")

    private fun mint(atMs: Long) {
        val ref = latestRef
        val sample = Sample(
            atMs = atMs,
            raw017 = latest017?.takeIf { atMs - it.atMs <= DriveLog.MAX_FIX_AGE_MS }?.value,
            raw013 = latest013?.takeIf { atMs - it.atMs <= DriveLog.MAX_FIX_AGE_MS }?.value,
            refKmh = ref?.kmh,
            refAtMs = ref?.atMs,
            source = if (ref == null) Source.NONE else Source.OBD_PID_0D,
        )

        samples.addLast(sample)
        while (samples.size > maxSamples) {
            samples.removeFirst()
        }
    }

    companion object {
        private const val SOURCE_017 = "0x17"

        /** Fewer bands than this is a cluster, not a range. Two is the floor for a slope. */
        private const val MIN_BANDS = 2

        /** ~10 Hz for `0x13` means an hour is 36 000 samples; keep the last twenty minutes. */
        const val MAX_SAMPLES = 12_000
    }
}
