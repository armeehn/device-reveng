package com.ripostelabs.carlauncher.carlib

/**
 * Rows for a speed-calibration drive, and the rule that decides which of them may be used.
 *
 * ── Why this type exists at all ─────────────────────────────────────────────────────────────────
 * The launcher decodes two speed candidates, `0x17` and `0x13`, and neither is calibrated. The
 * previous attempt to calibrate them produced a **wrong answer that looked right**: a 5.7-minute
 * GPS dropout was interpolated across, and the resulting straight line correlated beautifully with
 * a field that is not road speed at all. The conclusion survived until a second drive contradicted
 * it.
 *
 * So the job here is not to record numbers. It is to make that specific mistake impossible:
 * every row carries the AGE of the reference reading it was paired with, and
 * [usableForCalibration] refuses any row whose reference is stale. A gap in the data is then
 * visible as a gap, rather than silently becoming a line through it.
 *
 * ── The reference is OBD, not GPS ───────────────────────────────────────────────────────────────
 * **OBD-II PID 0x0D returns road speed in km/h directly**, and all three ECUs on this bus answer
 * it. That is a far better reference than GPS: it comes off the same bus on the same timebase, it
 * needs no scale of its own, and it cannot drop out under a bridge — which is precisely how the
 * last calibration went wrong.
 *
 * The staleness rule is kept anyway, because the failure it guards is not specific to GPS. A
 * request/response reference has its own gaps: a dropped or NAKed query leaves the last answer
 * sitting there looking current, and pairing a fresh raw value with a stale reply is the same
 * error wearing different clothes. [Source] records which reference a row used so a later reader
 * can tell them apart.
 *
 * Pure Kotlin. The file writing and the wiring live elsewhere; what is worth testing is the rule.
 */
object DriveLog {

    /**
     * How old a reference reading may be and still anchor a row.
     *
     * At 100 km/h a car covers 55 m in two seconds, so a reading older than this describes a speed
     * the car has already left. Being strict costs rows; being lax costs the whole calibration,
     * which is what happened last time.
     */
    const val MAX_FIX_AGE_MS = 2_000L

    /** Where a row's trusted km/h came from. Recorded so a mixed log stays interpretable. */
    enum class Source { OBD_PID_0D, GPS, NONE }

    /**
     * Below this the reference says little about a raw CAN value.
     *
     * For GPS that is noise. For OBD PID 0x0D it is quantisation: the PID reports whole km/h, so
     * at low speed one count is a large fraction of the reading and the fit is dominated by
     * rounding rather than by the relationship being measured.
     */
    const val MIN_USABLE_KMH = 5.0

    const val HEADER = "at_ms,raw_0x17,raw_0x13,ref_kmh,ref_source,ref_age_ms,usable,reason"

    data class Sample(
        val atMs: Long,
        /** Raw `0x17 p[0:1]`, or null when that frame has not arrived. */
        val raw017: Int?,
        /** Raw `0x13 p[0:1]`, or null when that frame has not arrived. */
        val raw013: Int?,
        /** Trusted km/h, or null when the reference gave no answer. */
        val refKmh: Double?,
        /** When the reading backing [refKmh] was taken. Null when there is none. */
        val refAtMs: Long?,
        /** Which reference produced [refKmh]. */
        val source: Source = Source.OBD_PID_0D,
    )

    /** Why a sample cannot anchor a calibration point. Empty string when it can. */
    fun rejection(s: Sample): String = when {
        s.refKmh == null || s.refAtMs == null -> "no_reference"
        s.atMs - s.refAtMs > MAX_FIX_AGE_MS -> "stale_reference"
        s.refKmh < MIN_USABLE_KMH -> "too_slow"
        s.raw017 == null && s.raw013 == null -> "no_can_speed"
        else -> ""
    }

    fun usableForCalibration(s: Sample): Boolean = rejection(s).isEmpty()

    /**
     * One CSV row. Unusable samples are written too, with the reason — a dropout that is recorded
     * as a dropout is evidence, whereas one that is simply absent is indistinguishable from the
     * car having been switched off, and that ambiguity is what makes a bad fit believable.
     */
    fun row(s: Sample): String {
        val age = if (s.refAtMs == null) "" else (s.atMs - s.refAtMs).toString()
        val reason = rejection(s)
        return listOf(
            s.atMs.toString(),
            s.raw017?.toString() ?: "",
            s.raw013?.toString() ?: "",
            s.refKmh?.let { "%.2f".format(it) } ?: "",
            s.source.name,
            age,
            if (reason.isEmpty()) "1" else "0",
            reason,
        ).joinToString(",")
    }

    /**
     * A scale in km/h per LSB, from usable samples only, or null if there are too few.
     *
     * Deliberately least-squares through the origin rather than a full linear fit: a speed field
     * that reads non-zero at a standstill is not a speed field, so an intercept would paper over
     * exactly the disqualifying evidence.
     */
    fun scaleKmhPerLsb(samples: List<Sample>, pick: (Sample) -> Int?): Double? {
        val pts = samples.filter(::usableForCalibration)
            .mapNotNull { s -> pick(s)?.let { raw -> raw.toDouble() to s.refKmh!! } }
            .filter { it.first != 0.0 }
        // Two points is a line through anything. The last wrong answer rested on a 2-point fit.
        if (pts.size < MIN_POINTS) {
            return null
        }
        val num = pts.sumOf { it.first * it.second }
        val den = pts.sumOf { it.first * it.first }
        return if (den == 0.0) null else num / den
    }

    /** Fewer than this and a "calibration" is a coincidence. */
    const val MIN_POINTS = 8

    /**
     * How many 10 km/h bands the usable samples actually visited.
     *
     * This asks for RANGE, not for steady cruising. Holding a fixed speed on a public road to
     * please a dataset is dangerous and is not required here: because the reference comes off the
     * same bus at the same instant as the raw value, transients pair correctly and ordinary
     * driving — traffic, braking, accelerating — covers the range better than a held speed would.
     *
     * The check exists only to catch the degenerate case where a whole log sits in one band, which
     * fits a line through a single cluster and means nothing.
     */
    fun distinctSpeedBands(samples: List<Sample>, bandKmh: Double = 10.0): Int =
        samples.filter(::usableForCalibration)
            .mapNotNull { it.refKmh }
            .map { (it / bandKmh).toInt() }
            .distinct()
            .size
}
