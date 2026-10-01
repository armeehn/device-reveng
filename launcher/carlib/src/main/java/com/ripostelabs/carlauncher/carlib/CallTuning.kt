package com.ripostelabs.carlauncher.carlib

/**
 * RAV4-184: call audio, as the BT module's echo canceller reads it.
 *
 *     launcher ──▶ car service (system uid) ──setprop──▶ persist.blinkbt.aec.delay        ──▶ audio HAL
 *                                                   ├──▶ persist.blinkbt.carplay.aecdelay ──▶ libblinkAec
 *                                                   └──▶ persist.blinkbt.aec.gain         ──▶ libblinkAec
 *
 * Only the system uid may set these props, so on Riposte OS 0.2 the car service writes them; on
 * the stock image eventcenter does. Values follow stock: `EventService.onSetMicGain`
 * (EventService.java:14836) and `SystemUtils.initSysBTLaunchSound` (SystemUtils.java:310-332).
 */
enum class AecPath(val code: Int, val prop: String) {
    /** Phone calls through the BT module. */
    PHONE(1, "persist.blinkbt.aec.delay"),

    /** CarPlay calls (ZLink). */
    CARPLAY(2, "persist.blinkbt.carplay.aecdelay");

    companion object {
        fun of(code: Int): AecPath? = entries.firstOrNull { it.code == code }
    }
}

/** Stock's five mic gain steps: the picker's level and the value the prop takes. */
enum class MicGain(val level: Int, val value: Int) {
    G85(1, 85),
    G90(2, 90),
    G96(3, 96),
    G100(4, 100),
    G112(5, 112);

    companion object {
        fun of(level: Int): MicGain? = entries.firstOrNull { it.level == level }

        /** The step a prop value names, or null for an unset or foreign value. */
        fun parse(prop: String): MicGain? {
            val value = prop.trim().toIntOrNull() ?: return null
            return entries.firstOrNull { it.value == value }
        }
    }
}

object CallTuning {

    const val MIC_GAIN_PROP = "persist.blinkbt.aec.gain"

    /** Stock clamps both delays to 0..1000 ms. */
    const val MAX_DELAY_MS = 1000

    /** The stock slider's step. */
    const val DELAY_STEP_MS = 10

    /** The prop value for [ms]; refuses what stock would clamp, so a bad value is never stored. */
    fun delayValue(ms: Int): String {
        require(ms in 0..MAX_DELAY_MS) { "echo delay takes 0..$MAX_DELAY_MS ms, not $ms" }
        return ms.toString()
    }

    /** A delay prop in ms, or null when unset or out of range. */
    fun parseDelay(prop: String): Int? = prop.trim().toIntOrNull()?.takeIf { it in 0..MAX_DELAY_MS }
}
