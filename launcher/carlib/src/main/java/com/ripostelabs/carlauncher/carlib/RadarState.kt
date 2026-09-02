package com.ripostelabs.carlauncher.carlib

/**
 * RadarState — a display-only snapshot of the parking-sensor frame canbus2 broadcasts as
 * `MCU_CAR_CAN_RADAR_INFO` with a byte[] under `EventUtils.CAR_CAN_DATA` (CAR_API §1.3).
 *
 * Frame layout, from the decompile (`CanDataParseBase.java:1221-1229`, `sendRadarInfo`):
 *
 *   byte[0]      — constant 1 (header)
 *   byte[1..4]   — FRONT bank, sensor index 0..3
 *   byte[5..8]   — REAR  bank, sensor index 0..3
 *
 * Each sensor byte is a DISTANCE CODE, not a bar count (`HiworldCanParseToyota.java:903-921`):
 * the CAN box reports a level 1..5 and the parser stores `level * 30`, so 30 is the closest band
 * and 150 the farthest; anything outside 1..5 is stored as 0xA0 = 160 = clear. 0 never leaves the
 * parser and is treated here as "no data". Smaller = closer. The vendor overlay agrees: ≤32 red,
 * ≤64 orange, ≤160 yellow, 160 = idle line (`BackcarEvent.java:960-975`).
 *
 * [front] and [rear] hold the decoded proximity BAND per sensor, 0 = clear/no data … [LEVEL_MAX]
 * = closest, so consumers can keep treating 0 as "nothing there" and larger as nearer.
 *
 * UNVERIFIED: the left→right order of the four sensors within a bank. The parser copies the CAN
 * box's index order untouched and nothing in the decompile names a side. The launcher's default is
 * index 0 = left; the maneuvering side-strip, which turns that guess into a safety claim, stays
 * behind `LauncherSettings.radarLayoutConfirmed` until a car settles it.
 */
data class RadarState(
    val valid: Boolean = false,
    /** FRONT proximity bands, index order as sent (assumed left→right). 0 = clear, higher = closer. */
    val front: List<Int> = emptyList(),
    /** REAR proximity bands, index order as sent (assumed left→right). 0 = clear, higher = closer. */
    val rear: List<Int> = emptyList(),
) {
    /** True if any sensor (front or rear) is reporting an obstacle. */
    fun hasObstacle(): Boolean = (front + rear).any { it > 0 }

    /** Normalized proximity for a band: 0f (clear) … 1f (closest band). */
    fun proximity(level: Int): Float =
        (level.coerceIn(0, LEVEL_MAX).toFloat() / LEVEL_MAX)

    /** v2.8 — which side of the car, for the maneuvering side-strips. */
    enum class Edge { LEFT, RIGHT }

    /** v2.8 — which sensor bank. */
    enum class Bank { FRONT, REAR }

    /**
     * v2.8 — the *closest* reading among the sensors on one corner of the car, 0f…1f.
     *
     * The strip has one arc group per corner where the bank has two or four sensors, so it has to
     * collapse them. It takes the maximum rather than a mean: a mean lets one clear sensor talk a
     * neighbouring obstacle down, and the only useful summary of "how close is anything on my left
     * front" is the nearest thing there.
     *
     * The left→right split rests on the UNVERIFIED index order (class KDoc). An odd sensor count
     * puts the middle sensor in both halves, which is right: a centre sensor covers both corners.
     */
    fun edgeProximity(edge: Edge, bank: Bank): Float {
        val levels = if (bank == Bank.FRONT) front else rear
        if (levels.isEmpty()) {
            return 0f
        }

        val half = (levels.size + 1) / 2
        val side = if (edge == Edge.LEFT) levels.take(half) else levels.takeLast(half)
        return proximity(side.max())
    }

    companion object {
        /** Number of proximity bands: distance codes 30/60/90/110/150 → bands 5..1. */
        const val LEVEL_MAX = 5

        /** Closest distance code the parser emits (CAN level 1 × 30). */
        const val CODE_NEAREST = 30
        /** 0xA0: no object in range. Anything at or beyond it is clear. */
        const val CODE_CLEAR = 0xA0
        /** Never emitted by the parser; a 0 means no data. */
        const val CODE_NONE = 0

        /** Upper bound of each band, nearest first. 110 is the parser's own irregular step. */
        private val BAND_CEILINGS = intArrayOf(30, 60, 90, 110, 150)

        private const val FRONT_START = 1
        private const val FRONT_END = 5   // exclusive → bytes 1..4
        private const val REAR_START = 5
        private const val REAR_END = 9    // exclusive → bytes 5..8

        /**
         * Distance code → proximity band. 0 and ≥ 0xA0 are clear; otherwise the band whose ceiling
         * the code first fits under, counted from the nearest, so 30 → 5 and 150 → 1.
         */
        fun band(code: Int): Int {
            if (code == CODE_NONE || code >= CODE_CLEAR) {
                return 0
            }
            val index = BAND_CEILINGS.indexOfFirst { code <= it }
            if (index < 0) {
                return 0
            }
            return LEVEL_MAX - index
        }

        /**
         * Decode the `CAR_CAN_DATA` radar frame. Returns an invalid (placeholder) state when the
         * frame is missing or too short.
         */
        fun fromRadarData(bytes: ByteArray?): RadarState {
            if (bytes == null || bytes.size < 2) return RadarState(valid = false)
            fun u(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else 0

            val front = (FRONT_START until minOf(FRONT_END, bytes.size)).map { band(u(it)) }
            val rear = (REAR_START until minOf(REAR_END, bytes.size)).map { band(u(it)) }

            // Valid as long as we got at least one sensor slot from the frame; the presence of
            // the broadcast itself is the real "radar active" signal.
            val valid = front.isNotEmpty() || rear.isNotEmpty()
            return RadarState(valid = valid, front = front, rear = rear)
        }
    }
}
