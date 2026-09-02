package com.ripostelabs.carlauncher.carlib

/**
 * Steering angle decoded from the CANBOX digest, stamped with when it arrived.
 *
 * RAV4-38: the v3.0 dashboard tile stayed on "no reading yet" for good. [CarEvents.steeringAngle]
 * was fed only by `ZXW_CAN_WHEEL_TRACK_EVT` under GUESSED extra keys, which never matched, while
 * the 0x11 Basic Status frames the capture screen decodes carried the angle all along. This is
 * that decode, lifted out of the capture screen so one source feeds both.
 *
 * Scale is the OEM's (raw/14, see `HiworldCanDecoder.decodeBasicStatus`); the sign convention is
 * not confirmed. Pure Kotlin, so the plumbing is unit-testable off-device.
 */
data class SteeringReading(
    /** Degrees on the OEM scale. Sign convention (left/right) unconfirmed. */
    val degrees: Double,
    /** Arrival time of the 0x11 frame, `System.currentTimeMillis()`. */
    val atMs: Long,
) {
    /**
     * True once no 0x11 frame has arrived for [STALE_AFTER_MS]. The digest repeats the angle
     * continuously, so silence means the feed is gone, not that the wheel is still.
     */
    fun isStale(nowMs: Long): Boolean = nowMs - atMs >= STALE_AFTER_MS

    companion object {
        /** Same window as `GpsSpeedSource`: this long without a frame is unknown, not frozen. */
        const val STALE_AFTER_MS = 5_000L

        /**
         * The angle carried by a framed CANBOX packet, or null when the frame is not 0x11 or
         * fails its checksum. Null means "no update" — a TPMS frame must not clear steering.
         */
        fun fromFrame(framed: ByteArray, atMs: Long): SteeringReading? {
            val basic = HiworldCanDecoder.decodeFrame(framed) as? CanSignal.BasicStatus
                ?: return null
            return SteeringReading(basic.steerAngleDeg, atMs)
        }
    }
}
