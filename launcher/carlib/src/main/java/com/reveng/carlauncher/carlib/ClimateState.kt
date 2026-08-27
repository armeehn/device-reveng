package com.reveng.carlauncher.carlib

/**
 * ClimateState — a small, display-only snapshot of the car's HVAC (CAR_API §5).
 *
 * The vendor gateway broadcasts a `com.szchoiceway.canbus.CarAirState` Parcelable on
 * `com.szchoiceway.canbus.carairstruct` and also answers AIDL `getAirData(int, byte[])`
 * with a raw A/C frame. We cannot deserialize the Parcelable (its class is not bundled in
 * this build), so the read path here is the AIDL byte[] frame.
 *
 * ⚠ BYTE-LAYOUT CAVEAT: the exact offsets of `getAirData()`'s returned frame were NOT
 * recovered from the decompile — [fromAirData] uses a *best-effort, GUESSED* layout derived
 * from the documented `CarAirState` field order (`bAcOn, bAutoOn2, bDualOn, byAirMode,
 * byFunStrength, byLeftColdLevel, byRightColdLevel …`, CAR_API §5). Treat every field as
 * unverified until checked on-device. The card degrades to a placeholder when [valid] is
 * false, so a wrong frame never fabricates confident numbers.
 */
data class ClimateState(
    val valid: Boolean = false,
    val acOn: Boolean = false,
    val autoOn: Boolean = false,
    val dualOn: Boolean = false,
    val airMode: Int = 0,
    val fanLevel: Int = 0,
    /** Set temperatures in the raw units the frame carries (usually °C×2 or a code). */
    val leftTempRaw: Int = -1,
    val rightTempRaw: Int = -1,
    val rearAirOn: Boolean = false,
) {
    /** Human-ish set-temp string, or "--" when we have no plausible value. */
    fun leftTempLabel(): String = tempLabel(leftTempRaw)
    fun rightTempLabel(): String = tempLabel(rightTempRaw)

    private fun tempLabel(raw: Int): String {
        if (raw < 0) return "--"
        // GUESS: many of these MCUs encode set-temp as (°C - 16) in half-degree steps, or
        // as a direct °C. If the raw looks like a plausible cabin temp already, show it;
        // otherwise decode the common (16 + raw*0.5) form.
        val direct = raw
        val decoded = 16.0 + raw * 0.5
        return when {
            direct in 16..32 -> "$direct°"
            decoded in 16.0..32.0 -> "%.0f°".format(decoded)
            else -> "--"
        }
    }

    companion object {
        /**
         * Best-effort decode of the `getAirData()` byte frame. Returns an invalid state
         * (placeholder) when the frame is missing/too short. Offsets are GUESSED — see the
         * class KDoc caveat.
         */
        fun fromAirData(bytes: ByteArray?): ClimateState {
            if (bytes == null || bytes.size < 6) return ClimateState(valid = false)
            fun u(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else 0
            // GUESSED offsets, following the documented CarAirState field order.
            val acOn = u(0) and 0x01 != 0
            val autoOn = u(0) and 0x02 != 0
            val dualOn = u(0) and 0x04 != 0
            val rearOn = u(0) and 0x08 != 0
            val airMode = u(1)
            val fan = u(2)
            val leftT = u(3)
            val rightT = u(4)
            // Consider the frame "valid" only if it carries at least one plausible signal.
            val plausible = fan in 0..12 && (leftT in 0..64)
            return ClimateState(
                valid = plausible,
                acOn = acOn,
                autoOn = autoOn,
                dualOn = dualOn,
                airMode = airMode,
                fanLevel = fan.coerceIn(0, 8),
                leftTempRaw = leftT,
                rightTempRaw = rightT,
                rearAirOn = rearOn,
            )
        }
    }
}
