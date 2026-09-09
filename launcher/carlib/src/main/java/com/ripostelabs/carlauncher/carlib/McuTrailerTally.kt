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

    private val _samples = ArrayList<String>()

    /** Hex of the first disagreeing bodies, oldest first. */
    val samples: List<String> get() = _samples

    private val seen get() = agrees + disagrees + short

    /** Count one broadcast body. Due on the first disagreement and on every [every]th body. */
    fun onBody(body: ByteArray): Report {
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
        val sample = _samples.firstOrNull()?.let { " sample=$it" } ?: ""
        return "mcu-trailer agree=$agrees disagree=$disagrees short=$short verdict=\"$verdict\"$sample"
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
