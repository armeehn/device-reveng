package com.reveng.carlauncher.carlib

/**
 * v2.8 — the raw side of the parking-radar frame, for the on-device capture view.
 *
 * [RadarState] decodes `MCU_CAR_CAN_RADAR_INFO` with a byte layout that is **GUESSED** and has
 * never been checked against a car. This file exists to make that check possible: it keeps the
 * bytes as they arrived, and accumulates which of them actually move.
 *
 * Deliberately a plain class, not a `data class`: [StateFlow] conflates by `equals`, and
 * `ByteArray.equals` is identity — but a data class's generated `equals` would compare the
 * *reference* too while looking like it compares contents. A plain class makes that explicit, and
 * every frame reaching the collector is what the capture view needs.
 */
class RadarFrame(val bytes: ByteArray, val atMs: Long)

/**
 * What one byte offset has done since the capture was reset.
 *
 * [min] / [max] answer the question the guessed layout cannot: walk an obstacle toward one corner
 * of the car and exactly one byte should sweep. [changes] separates a byte that moved once (a
 * status flag flipping when the sensors armed) from one that tracks distance continuously.
 */
data class RadarByteStat(
    val index: Int,
    val value: Int,
    val min: Int,
    val max: Int,
    val changes: Int,
) {
    /** True once this offset has held more than one value — the signal the capture is hunting. */
    val moved: Boolean get() = min != max
}

/**
 * An immutable accumulation over the frames seen since the last reset.
 *
 * Immutable rather than a mutable accumulator because Compose reads it: a mutable object updated
 * in place would need its own snapshot state per byte, and the frames arrive at a handful per
 * second, so rebuilding a short list costs nothing worth the complexity.
 */
data class RadarCapture(
    val frames: Int = 0,
    val payloadSize: Int = 0,
    val bytes: List<RadarByteStat> = emptyList(),
) {

    /** Fold one raw frame in. Offsets past [MAX_BYTES] are dropped — see the constant. */
    fun accept(frame: ByteArray): RadarCapture {
        val width = minOf(frame.size, MAX_BYTES)
        val next = (0 until width).map { i ->
            val value = frame[i].toInt() and 0xFF
            val prior = bytes.getOrNull(i)
            if (prior == null) {
                return@map RadarByteStat(i, value, value, value, changes = 0)
            }
            RadarByteStat(
                index = i,
                value = value,
                min = minOf(prior.min, value),
                max = maxOf(prior.max, value),
                changes = prior.changes + if (prior.value != value) 1 else 0,
            )
        }

        return RadarCapture(frames = frames + 1, payloadSize = frame.size, bytes = next)
    }

    companion object {
        /**
         * A CAN frame is 8 bytes and the gateway hands us one payload; 32 covers a multi-frame
         * aggregate without letting a malformed extra paint an unreadable wall of hex.
         */
        const val MAX_BYTES = 32
    }
}
