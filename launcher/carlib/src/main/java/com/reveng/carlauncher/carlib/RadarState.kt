package com.reveng.carlauncher.carlib

/**
 * RadarState — a small, display-only snapshot of the parking-sensor (ultrasonic radar)
 * frame the vendor gateway broadcasts as `MCU_CAR_CAN_RADAR_INFO` with a raw byte[] under
 * `EventUtils.CAR_CAN_DATA` (CAR_API §1.3, §6.3 "Reverse / radar"; consumed in the gateway
 * at `EvtModel.java:525-529`).
 *
 * ⚠ BYTE-LAYOUT CAVEAT: the exact frame layout was NOT recovered from the decompile. The
 * spec only says "byte[] raw radar frame, per-sensor distances". [fromRadarData] therefore
 * uses a *best-effort, GUESSED* layout and MUST be verified on-device:
 *
 *   byte[0]      — status / header (sensor bank enabled, beep flag …)  [GUESSED, skipped]
 *   byte[1..4]   — FRONT sensors, left→right (FL, FCL, FCR, FR)        [GUESSED]
 *   byte[5..8]   — REAR  sensors, left→right (RL, RCL, RCR, RR)        [GUESSED]
 *
 * and each sensor byte is treated as a proximity LEVEL where **0 = clear/no obstacle** and a
 * larger value = closer (typical parking-sensor "bar count", capped at [LEVEL_MAX]). If the
 * MCU actually encodes *distance* (larger = farther) the color ramp is simply inverted — the
 * layout is annotated GUESSED so this is easy to flip after a live capture.
 *
 * The UI ([RadarView]) only appears once a real frame arrives (StateFlow starts null), so a
 * wrong guess never fabricates confident readings out of thin air.
 */
data class RadarState(
    val valid: Boolean = false,
    /** FRONT sensor levels, left→right. 0 = clear, higher = closer. May be empty. */
    val front: List<Int> = emptyList(),
    /** REAR sensor levels, left→right. 0 = clear, higher = closer. May be empty. */
    val rear: List<Int> = emptyList(),
) {
    /** True if any sensor (front or rear) is reporting an obstacle. */
    fun hasObstacle(): Boolean = (front + rear).any { it > 0 }

    /**
     * Normalized proximity for a raw sensor level: 0f (clear/far) … 1f (very close).
     * GUESS: level is a bar-count 0..[LEVEL_MAX]. Verify polarity on-device.
     */
    fun proximity(level: Int): Float =
        (level.coerceIn(0, LEVEL_MAX).toFloat() / LEVEL_MAX)

    companion object {
        /** Guessed max bar-count per sensor. */
        const val LEVEL_MAX = 8

        private const val FRONT_START = 1
        private const val FRONT_END = 5   // exclusive → bytes 1..4
        private const val REAR_START = 5
        private const val REAR_END = 9    // exclusive → bytes 5..8

        /**
         * Best-effort decode of the `CAR_CAN_DATA` radar frame. Returns an invalid
         * (placeholder) state when the frame is missing/too short. Offsets are GUESSED —
         * see the class KDoc caveat.
         */
        fun fromRadarData(bytes: ByteArray?): RadarState {
            if (bytes == null || bytes.size < 2) return RadarState(valid = false)
            fun u(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else 0

            // A byte of 0xFF is commonly "sensor off / no data" — treat as clear (0).
            fun level(i: Int): Int = u(i).let { if (it == 0xFF) 0 else it.coerceIn(0, LEVEL_MAX) }

            val front = (FRONT_START until minOf(FRONT_END, bytes.size)).map { level(it) }
            val rear = (REAR_START until minOf(REAR_END, bytes.size)).map { level(it) }

            // Consider valid as long as we got at least one sensor slot from the frame; the
            // presence of the broadcast itself is the real "radar active" signal.
            val valid = front.isNotEmpty() || rear.isNotEmpty()
            return RadarState(valid = valid, front = front, rear = rear)
        }
    }
}
