package com.ripostelabs.carlauncher.carlib

/**
 * DspSound — the stock DSP app's sound blocks beyond the EQ (`com.choiceway.dsp`, chip 0, the
 * GT6 sound path). Each block is one `4F` sub-id frame and a few of the app's own rows.
 *
 *     McuSetupStore.setDspSub / setDspBass ──▶ McuSetupProtocol.dspSub / dspBass
 *                                          ──▶ `4F 15 …` / `4F 16 …` ──▶ MCU
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
