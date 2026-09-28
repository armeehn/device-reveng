package com.ripostelabs.carlauncher.data

/**
 * SurroundSignal — is there a camera on a 360 tile, and which tile fills the screen.
 *
 * The XS9922B runs in its four-channel mode with no signal detection (os/CAMERA_360.md), so an
 * empty channel still streams: a flat blue frame. A tile is judged from two things the app can
 * see every second:
 *
 *     frame count (get_frame_count, per slot) ── stalled ────────────▶ NO_SIGNAL
 *     picture (a 32x18 sample of the texture) ── flat ───────────────▶ NO_SIGNAL
 *                                              └ advancing + detail ─▶ LIVE
 *
 * Flat = the 5th and 95th luma percentiles within [FLAT_SPREAD] levels: a few hot pixels do
 * not make a blank frame live, and a fisheye picture's black corners against its lit middle
 * spread far wider.
 */
object SurroundSignal {

    enum class Tile { WAITING, LIVE, NO_SIGNAL }

    /** True when the sampled ARGB pixels hold no picture, e.g. the decoder's no-video frame. */
    fun isFlat(argb: IntArray): Boolean {
        if (argb.isEmpty()) {
            return true
        }

        val luma = argb.map { luma(it) }.sorted()
        val low = luma[(luma.size - 1) * LOW_PERCENTILE / PERCENT]
        val high = luma[(luma.size - 1) * HIGH_PERCENTILE / PERCENT]

        return high - low <= FLAT_SPREAD
    }

    /** The tile's verdict from two frame counts a poll apart and the picture's flatness. */
    fun judge(before: Int?, now: Int?, flat: Boolean): Tile {
        if (now == null) {
            return Tile.WAITING
        }

        if (before != null && now <= before) {
            return Tile.NO_SIGNAL
        }

        return if (flat) Tile.NO_SIGNAL else Tile.LIVE
    }

    /** The full-screen tile after a tap: the tapped one, or none (the grid) when it already was. */
    fun focus(current: Int?, tapped: Int): Int? = if (current == tapped) null else tapped

    /** BT.601 luma, 0..255. */
    private fun luma(argb: Int): Int {
        val r = (argb shr RED_SHIFT) and CHANNEL
        val g = (argb shr GREEN_SHIFT) and CHANNEL
        val b = argb and CHANNEL

        return (R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b) / WEIGHT_TOTAL
    }

    /** Luma levels between the percentiles that still count as flat. */
    private const val FLAT_SPREAD = 12

    private const val LOW_PERCENTILE = 5
    private const val HIGH_PERCENTILE = 95
    private const val PERCENT = 100

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL = 0xFF

    private const val R_WEIGHT = 299
    private const val G_WEIGHT = 587
    private const val B_WEIGHT = 114
    private const val WEIGHT_TOTAL = 1000
}
