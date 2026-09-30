package com.ripostelabs.carlauncher.data

/** What the screensaver decision needs, sampled at one instant. */
data class SaverInputs(
    /** `SYS_AUTO_START_SCREENSAVER_TIME` in seconds; 0 = never ([PowerOptions.SCREEN_TIMEOUT]). */
    val timeoutS: Int,
    /** Time since the last touch, key or volume change. */
    val idleMs: Long,
    val accOn: Boolean,
    val reverse: Boolean,
    /** A call ringing or up: the incoming-call card must stay visible. */
    val callUp: Boolean,
    val moving: Boolean,
)

/**
 * RAV4-201 — when the launcher's own screensaver may cover the panel (Riposte OS 0.2 has no
 * vendor one; stock is `ScreensaverActivity.java:47`).
 *
 * It starts only when nothing needs the screen: parked, ACC on (ACC off is standby engaging),
 * no reverse, no call. Any of those arriving later also takes it down, so the reverse camera,
 * the call card and the volume bar never sit under it.
 */
object ScreensaverPolicy {

    private const val MS_PER_S = 1_000L

    fun shouldShow(i: SaverInputs): Boolean {
        if (i.timeoutS <= 0) {
            return false
        }
        if (!i.accOn || i.reverse || i.callUp || i.moving) {
            return false
        }
        return i.idleMs >= i.timeoutS * MS_PER_S
    }

    /**
     * Burn-in guard: the face moves to a new spot each minute, within +/-[range] px on each axis.
     * A fixed hash of the minute, so the spot is stable while the minute lasts.
     */
    fun drift(minuteOfDay: Int, range: Int): Pair<Int, Int> {
        val span = 2 * range + 1
        val x = Math.floorMod(minuteOfDay * DRIFT_X_STEP, span) - range
        val y = Math.floorMod(minuteOfDay * DRIFT_Y_STEP, span) - range
        return x to y
    }

    // Co-prime steps walk the whole span before repeating.
    private const val DRIFT_X_STEP = 37
    private const val DRIFT_Y_STEP = 53
}

/** Time since the last user activity, on a monotonic clock. Safe to touch from any thread. */
class IdleClock(private val now: () -> Long) {

    @Volatile
    private var last = now()

    fun touch() {
        last = now()
    }

    fun idleMs(): Long = now() - last
}
