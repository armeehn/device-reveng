package com.ripostelabs.carlauncher.carlib

/**
 * ReverseTrigger — when the reverse picture goes up and comes down, as eventcenter decides it.
 *
 *     71 bit 1 ───┐
 *     awake ──────┼─▶ line = bit && awake        (onCmdSysEvent, EventService.java:2354-2366)
 *                 │     line ↑ → HANDLER_BACKCAR_START (250): picture only if
 *     GPS speed ──┼─▶     threshold == 0 || speed <= threshold   (:689-693)
 *     threshold ──┘     line ↓ → HANDLER_BACKCAR_END (251): picture down, always
 *
 * The vendor posts the START message once per line change, so the speed test runs on the rising
 * edge only: a car that entered reverse above the threshold gets no picture until the line drops
 * and rises again, and a picture already up stays up whatever the speed does. "Awake" is the
 * vendor's `mAccOpenState`, cleared on HANDLER_ACC_POWER_OFF_EVT (:842-867) and on the sleep path
 * (:3586), set again on wake (:3448).
 *
 * Pure state, no clock, no port: the caller feeds every SYS_EVENT and reads [engaged].
 */
class ReverseTrigger {

    /** True while the picture should be up. */
    var engaged: Boolean = false
        private set

    /** What the last [onLine] did, for the log: most calls are speed ticks that move nothing. */
    var lastEdge: Edge = Edge.NONE
        private set

    enum class Edge { NONE, UP, UP_TOO_FAST, DOWN }

    private var line: Boolean = false

    /**
     * One `71` SYS_EVENT worth of inputs. [speedKmh] may be [GpsSpeedSource.SPEED_UNKNOWN], which
     * passes the gate the way the vendor's `mGpsSpeed = 0` default does. Returns [engaged].
     */
    fun onLine(reverseBit: Boolean, awake: Boolean, speedKmh: Int, thresholdKmh: Int): Boolean {
        val next = reverseBit && awake
        if (next == line) {
            lastEdge = Edge.NONE
            return engaged
        }

        line = next
        if (!next) {
            engaged = false
            lastEdge = Edge.DOWN
            return false
        }

        // Rising edge: the vendor's single speed check (HANDLER_BACKCAR_START, :689-693).
        if (thresholdKmh == THRESHOLD_OFF || speedKmh <= thresholdKmh) {
            engaged = true
        }
        lastEdge = if (engaged) Edge.UP else Edge.UP_TOO_FAST
        return engaged
    }

    companion object {
        /** A threshold of 0 km/h means "no speed gate" (getBackCarSpeedThreshold, :9004-9016). */
        const val THRESHOLD_OFF = 0

        /** `Sys_Backcar_speed_threshold` index → km/h: 0 off, 1 → 30, 2 → 50, 3 → 80 (:9004-9016). */
        fun thresholdKmh(setting: Int): Int = when (setting) {
            1 -> 30
            2 -> 50
            3 -> 80
            else -> THRESHOLD_OFF
        }
    }
}
