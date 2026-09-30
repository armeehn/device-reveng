package com.ripostelabs.carlauncher.carlib

/**
 * DspSound — the stock DSP app's sound blocks beyond the EQ (`com.choiceway.dsp`, chip 0, the
 * GT6 sound path). Each block is one `4F` sub-id frame and a few of the app's own rows.
 *
 *     McuSetupStore.setDspSub / setDspBass / setDspField
 *         ──▶ McuSetupProtocol.dspSub / dspBass / dspDelay + dspChannelGain
 *         ──▶ `4F 15 …` / `4F 16 …` / `4F 12 …` + `4F 13 …` ──▶ MCU
 *     boot and wake ──▶ McuOwnerProtocol.configBlocks re-sends the saved blocks
 *
 * Ranges are the stock controls'; paths are under the app's `model/` and `fragment/`.
 */
object DspSound {

    /** Subwoofer cut-off, 20..250 Hz (SubWoofModel_Two.java:51-58). */
    const val SUB_FREQ_MIN = 20
    const val SUB_FREQ_MAX = 250

    /** Subwoofer gain 0..24 with 12 at 0 dB (:51-58; BassFragment_2_Two.java:68-72). */
    const val SUB_GAIN_MAX = 24
    const val SUB_GAIN_FLAT = 12

    /** Bass boost level 0..12 (fragment_bass_two.xml, `android:max="12"`). */
    const val BASS_LEVEL_MAX = 12

    /** Bass boost centre-frequency picker; index 0 is off (PickerView_Two.DEFAULT_DATA, :25). */
    val BASS_FREQS: List<String> = listOf(
        "Off", "≤ 20 Hz", "≤ 25 Hz", "≤ 30 Hz", "≤ 40 Hz", "≤ 50 Hz", "≤ 63 Hz",
        "≤ 80 Hz", "≤ 100 Hz", "≤ 125 Hz", "≤ 160 Hz", "≤ 200 Hz", "≤ 250 Hz",
    )

    /**
     * The subwoofer block, `4F 15 freq gain phase amp offOn` (sendStrongBass, :155-169).
     * [offOn] is stock's `OFF_ON`: sent, never set by any stock screen (always 0), so which
     * value means "on" is for the car to show.
     */
    data class Sub(
        val freq: Int = SUB_FREQ_MAX,
        val gain: Int = SUB_GAIN_FLAT,
        val reversePhase: Boolean = false,
        val amplifier: Boolean = false,
        val offOn: Boolean = false,
    ) {
        fun clamped(): Sub = copy(
            freq = freq.coerceIn(SUB_FREQ_MIN, SUB_FREQ_MAX),
            gain = gain.coerceIn(0, SUB_GAIN_MAX),
        )
    }

    /** The bass boost block, `4F 16 00 level freq` (BassModel_Two.sendFrequency, :126-140). */
    data class Bass(
        val level: Int = 0,
        val freq: Int = 0,
    ) {
        fun clamped(): Bass = copy(
            level = level.coerceIn(0, BASS_LEVEL_MAX),
            freq = freq.coerceIn(0, BASS_FREQS.lastIndex),
        )
    }

    /** Speaker distance 0..272 cm: the dial's 288 degrees * 34 / 36 (RotateDialScale_Two.java:198-214). */
    const val DELAY_MAX_CM = 272

    /** Sound travels 34 cm per ms; stock labels each distance with it (FieldFragment_Two.java:398). */
    const val CM_PER_MS = 34

    /** Channel gain slider 0..95 with 80 at 0 dB (layout_field_save_two.xml; Constants.java:195-199). */
    const val GAIN_MAX = 95
    const val GAIN_FLAT = 80

    /** The five outputs in wire order (sendDelay, :108-112); [label] is stock's lbl_* text. */
    enum class Speaker(val label: String) {
        LEFT_FRONT("Front left"),
        RIGHT_FRONT("Front right"),
        LEFT_REAR("Rear left"),
        RIGHT_REAR("Rear right"),
        CENTRE("Centre"),
    }

    /**
     * Stock's easy-mode seats and their `DRIVE_MODE` numbers (EasyFieldFragment_two.java:246-275).
     * [delays] are front-left, front-right, rear-left, rear-right in cm; the centre is kept.
     * CUSTOM (5) is what a dial move leaves (FieldFragment_Two.java:109).
     */
    enum class Seat(val mode: Int, val label: String, val delays: List<Int>?) {
        DRIVER(0, "Driver", listOf(0, 0, 0, HALF_CM)),
        PASSENGER(1, "Passenger", listOf(0, 0, HALF_CM, 0)),
        REAR(2, "Rear seats", listOf(DELAY_MAX_CM, DELAY_MAX_CM, 0, 0)),
        ALL(3, "All seats", listOf(0, 0, 0, 0)),
        CUSTOM(5, "Custom", null);

        companion object {
            fun of(mode: Int): Seat = values().firstOrNull { it.mode == mode } ?: ALL
        }
    }

    /**
     * The listening position: a distance and a gain per [Speaker], in [Speaker] order. Boot
     * sends it as `4F 12` (distances) and `4F 13` (gains). Defaults are stock's: 0 cm, 80
     * (0 dB), all seats (Constants.java:189-199).
     */
    data class Field(
        val delays: List<Int> = List(SPEAKERS) { 0 },
        val gains: List<Int> = List(SPEAKERS) { GAIN_FLAT },
        val seat: Seat = Seat.ALL,
    ) {
        fun clamped(): Field = copy(
            delays = List(SPEAKERS) { delays.getOrElse(it) { 0 }.coerceIn(0, DELAY_MAX_CM) },
            gains = List(SPEAKERS) { gains.getOrElse(it) { GAIN_FLAT }.coerceIn(0, GAIN_MAX) },
        )

        /** A stock seat loads its four delays; the centre keeps its own. CUSTOM changes nothing. */
        fun withSeat(next: Seat): Field {
            val set = next.delays ?: return this

            return copy(delays = set + delays.getOrElse(Speaker.CENTRE.ordinal) { 0 }, seat = next)
        }

        fun withDelay(speaker: Speaker, cm: Int): Field {
            val next = delays.toMutableList().also { it[speaker.ordinal] = cm.coerceIn(0, DELAY_MAX_CM) }
            return copy(delays = next, seat = Seat.CUSTOM)
        }

        fun withGain(speaker: Speaker, gain: Int): Field {
            val next = gains.toMutableList().also { it[speaker.ordinal] = gain.coerceIn(0, GAIN_MAX) }
            return copy(gains = next)
        }
    }

    private val SPEAKERS = Speaker.values().size
    private const val HALF_CM = DELAY_MAX_CM / 2

    private const val SUB_ROW_SEPARATOR = "|"

    /** Stock's "gain|freq" row (saveStrongBassValues, :108-113); it wrote floats, "12.0|250.0". */
    fun subRow(sub: Sub): String = "${sub.gain}$SUB_ROW_SEPARATOR${sub.freq}"

    /** A broken or missing row keeps the defaults, as initStrongBass does (:46-59). */
    fun parseSub(row: String?, base: Sub): Sub {
        val parts = row?.split(SUB_ROW_SEPARATOR)?.map { number(it) } ?: return base
        val gain = parts.getOrNull(0) ?: return base
        val freq = parts.getOrNull(1) ?: return base

        return base.copy(gain = gain, freq = freq).clamped()
    }

    /** Stock kept every value as a float string; an int string reads the same. */
    fun number(text: String?): Int? = text?.trim()?.toFloatOrNull()?.toInt()
}
