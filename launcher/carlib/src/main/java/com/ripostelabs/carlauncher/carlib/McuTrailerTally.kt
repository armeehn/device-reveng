package com.ripostelabs.carlauncher.carlib

/**
 * McuTrailerTally — the car's answer to a question the desk cannot settle.
 *
 * [McuSerial] says the byte behind every MCU body on the wire is `~(LEN + OPCODE + payload)`, and
 * that the vendor re-broadcasts the body with that byte still attached (the "C2" canbus2 ignores).
 * Both claims come from a decompile. The launcher already receives every broadcast body, so each
 * one is a free test of the formula, with no port opened and nothing sent.
 *
 *     broadcast body ──▶ onBody ──▶ agrees / disagrees / short
 *                                        │
 *                             first disagreement, then every REPORT_EVERY bodies
 *                                        ▼
 *                              summary() ──▶ logcat "Canable", pulled by the watcher on x
 *
 * A disagreeing body is kept, as hex, up to [MAX_SAMPLES]: a count says the formula is wrong, a
 * body says how. Pure Kotlin so the cadence and the wording are pinned on the JVM.
 *
 * ── Bodies that are not frames are not evidence ─────────────────────────────────────────────────
 * The car reported 861 agreements against 139 disagreements, and the disagreeing sample it sent
 * back was `00 00 00`. That is not a frame the formula got wrong: it carries no opcode, no payload
 * and no checksum. It cleared the length gate, failed arithmetic it was never part of, and counted
 * against the formula anyway.
 *
 * So an all-zero body is counted separately and kept out of the verdict. This does not make the
 * formula right — it stops a pile of padding being offered as proof that it is wrong, which is the
 * more dangerous error of the two: it would send someone to rewrite working arithmetic.
 */
class McuTrailerTally(private val every: Int = REPORT_EVERY) {

    /** Whether the caller should log [summary] now. */
    enum class Report { QUIET, DUE }

    var agrees = 0
        private set
    var disagrees = 0
        private set
    var short = 0
        private set

    /**
     * Bodies with no non-zero byte at all. Counted, never judged: see the note above.
     *
     * Reported rather than dropped silently, because a broadcast stream that is mostly padding is
     * itself worth knowing about, and hiding it would make this tally look quieter than the truth.
     */
    var blank = 0
        private set

    private val _samples = ArrayList<String>()

    /** Hex of the first disagreeing bodies, oldest first. */
    val samples: List<String> get() = _samples

    private val seen get() = agrees + disagrees + short + blank

    /** Count one broadcast body. Due on the first disagreement and on every [every]th body. */
    fun onBody(body: ByteArray): Report {
        if (body.isNotEmpty() && body.all { it.toInt() == 0 }) {
            blank++
            return if (seen % every == 0) Report.DUE else Report.QUIET
        }

        val verdict = McuSerial.trailer(body)
        when (verdict) {
            McuSerial.Trailer.AGREES -> agrees++
            McuSerial.Trailer.DISAGREES -> disagrees++
            McuSerial.Trailer.SHORT -> short++
        }

        val firstDisagree = verdict == McuSerial.Trailer.DISAGREES && disagrees == 1
        if (verdict == McuSerial.Trailer.DISAGREES && _samples.size < MAX_SAMPLES) {
            _samples.add(hex(body))
        }

        return if (firstDisagree || seen % every == 0) Report.DUE else Report.QUIET
    }

    /** One logcat line. The verdict is spelled out so a grep on the pull reads it without a key. */
    fun summary(): String {
        val verdict = when {
            seen == 0 -> "no bodies yet"
            disagrees == 0 -> "outer CK formula HOLDS so far"
            else -> "outer CK formula DISAGREES"
        }
        // Every kept sample, not just the first. One body says the formula disagreed somewhere;
        // three say whether the disagreements look alike, which is the difference between a bug
        // to fix and a stream to filter. The class promised this and printed one.
        val sample = if (_samples.isEmpty()) "" else " samples=[" + _samples.joinToString(" | ") + "]"
        return "mcu-trailer agree=$agrees disagree=$disagrees short=$short blank=$blank " +
            "verdict=\"$verdict\"$sample"
    }

    private fun hex(body: ByteArray): String =
        body.take(SAMPLE_BYTES).joinToString(" ") { "%02X".format(it) }

    private companion object {
        /** MCU bodies arrive tens of times a second; one line a minute or so is plenty. */
        const val REPORT_EVERY = 1000
        const val MAX_SAMPLES = 3
        const val SAMPLE_BYTES = 64
    }
}
