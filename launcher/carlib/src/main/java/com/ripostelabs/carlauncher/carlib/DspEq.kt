package com.ripostelabs.carlauncher.carlib

/**
 * DspEq — the stock DSP app's 48-band graphic EQ (`com.choiceway.dsp`, chip 0, the GT6 sound
 * path), its preset curves and the rows it kept them in.
 *
 *     McuSetupStore.setDspPreset / setDspBand ──▶ McuSetupProtocol.dspEq / dspEqBand
 *                                              ──▶ `4F 10 g0..g47` / `4F 11 band g` ──▶ MCU
 *
 * Gains are dB, -10..+10, 0 flat. On the wire each band is gain + 10 (EqModel_Two_48.java:
 * 200-207). Curves are Constants.java's `*_TWO_48` strings, copied as data.
 */
object DspEq {

    const val BANDS = 48
    const val GAIN_MIN = -10
    const val GAIN_MAX = 10

    /** Stock `Mode.NULL`: a curve edited band by band, no preset selected (Constants.java:211). */
    const val EDITED = -1

    /** How many custom slots stock keeps (sp_eq_mode_custom1..3_two). */
    const val CUSTOM_SLOTS = 3

    /** Band centres, Constants.EQ_FREQ_LIST_TWO_48 (:23), for labels. */
    val FREQUENCIES: List<String> = listOf(
        "16", "20", "25", "30", "35", "40", "50", "60", "70", "80", "95", "100",
        "125", "150", "175", "200", "235", "250", "300", "375", "400", "500", "600", "700",
        "800", "920", "1.1k", "1.2k", "1.3k", "1.7k", "2k", "2.4k", "2.8k", "3.2k", "3.8k", "4.4k",
        "5k", "6k", "7k", "8k", "9.3k", "11k", "12.5k", "14k", "15k", "16k", "17k", "18k",
    )

    val FLAT: List<Int> = List(BANDS) { 0 }

    /**
     * The stock presets in `Constants.Mode` order (:210-223); [index] is what stock saved in
     * sp_eq_mode_index_two. Custom slots 9..11 hold the user's own curves.
     */
    enum class Preset(val index: Int, val label: String, val curve: List<Int>?) {
        ROCK(0, "Rock", parse("3, 3, 4, 4, 5, 5, 5, 5, 2, 2, -2, -2, -4, -4, -4, -4, -3, -3, 1, 1, 3, 3, 4, 4, 5, 5, 5, 5, 4, 4, 3, -4, -4, -3, -3, 1, 1, 3, 3, 4, 4, 5, 5, 5, 5, 4, 4, 3")),
        POP(1, "Pop", parse("0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 3, 3, 1, 1, -1, -1, -2, -2, -2, -2, 0, 0, 1, 1, 2, 2, 3, 3, 2, 1, 1, -1, -1, -2, -2, -2, -2, 0, 0, 1, 1, 2, 2, 3, 3, 2")),
        CLASSICAL(2, "Classical", parse("0, 0, 2, 2, 1, 1, 2, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 3, 4, 4, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 3, 4, 4, 2")),
        VOCAL(3, "Vocal", parse("1, 1, 0, 0, -2, -2, -2, -2, -3, -3, -4, -4, -3, -3, -2, -2, -2, -2, -1, -1, -1, 0, 2, 2, 3, 3, 3, 3, 4, 3, 2, -2, -2, -2, -2, -1, -1, -1, 0, 2, 2, 3, 3, 3, 3, 4, 3, 2")),
        JAZZ(4, "Jazz", parse("1, 1, 4, 5, 4, 3, 4, 5, 2, 1, 0, -1, -2, -3, -1, 0, 0, 0, 0, 0, 1, 1, 2, 2, 1, 1, 2, 3, 3, 4, 2, -1, 0, 0, 0, 0, 0, 1, 1, 2, 2, 1, 1, 2, 3, 3, 4, 2")),
        FLAT(5, "Flat", DspEq.FLAT),
        FACTORY_1(6, "Factory 1", parse("7, 5, 5, 3, 2, 3, 6, 7, 2, 3, -1, -3, 0, 2, 4, 5, 5, 5, 3, 2, 3, 6, 7, 2, 3, -1, -3, 0, 2, 4, 5, 4, 5, 5, 5, 3, 2, 3, 6, 7, 2, 3, -1, -3, 0, 2, 4, 5")),
        FACTORY_2(7, "Factory 2", parse("4, 3, 3, 3, 2, 2, 2, 0, 2, 1, 1, -1, -1, -2, -2, -2, 3, 3, 3, 2, 2, 2, 0, 2, 1, 1, -1, -1, -2, -2, -2, -2, -2, 3, 3, 3, 2, 2, 2, 0, 2, 1, 1, -1, -1, -2, -2, -2")),
        FACTORY_3(8, "Factory 3", parse("5, 2, 4, -2, -2, 3, 6, 7, 2, 3, -1, -3, 0, 2, 6, 6, 2, 4, -2, -2, 3, 6, 7, 2, 3, -1, -3, 0, 2, 6, 6, 6, 6, 2, 4, -2, -2, 3, 6, 7, 2, 3, -1, -3, 0, 2, 6, 6")),
        CUSTOM_1(9, "Custom 1", null),
        CUSTOM_2(10, "Custom 2", null),
        CUSTOM_3(11, "Custom 3", null);

        /** 0..2 for a custom slot, else null. */
        val slot: Int? get() = if (curve == null) index - CUSTOM_1_INDEX else null

        companion object {
            private const val CUSTOM_1_INDEX = 9

            fun of(index: Int): Preset? = values().firstOrNull { it.index == index }

            fun custom(slot: Int): Preset = values().first { it.slot == slot }
        }
    }

    /** A band gain in range; anything else is clamped, never wrapped. */
    fun clamp(gain: Int): Int = gain.coerceIn(GAIN_MIN, GAIN_MAX)

    /** Stock's comma row; a short or broken row reads as flat, as initEQ does (:36-63). */
    fun parse(row: String?): List<Int> {
        val parts = row?.split(",")?.map { it.trim().toIntOrNull() } ?: return FLAT
        if (parts.size < BANDS || parts.any { it == null }) {
            return FLAT
        }

        return parts.take(BANDS).map { clamp(it!!) }
    }

    /** The comma row stock writes (saveEqValues, :131-148). */
    fun row(curve: List<Int>): String = curve.joinToString(",")
}
