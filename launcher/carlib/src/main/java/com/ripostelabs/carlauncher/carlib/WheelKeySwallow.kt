package com.ripostelabs.carlauncher.carlib

/**
 * Drop the vendor's plain key after we acted on a long press of the same button.
 *
 * The CAN app reports the key on the release frame, so a long press we already handled is
 * followed, within a few hundred ms, by the vendor doing what a short press does. Where that
 * arrives as something addressed to us it is dropped:
 *
 * ```
 *  ZXW_CAN_KEY_EVT ─▶ gateway ProcessCanKey (EventService.java:13021-13110)
 *      NEXT/PREV/PLAY ─▶ sendMediaKey ─▶ injected KeyEvent 87/88/85 ─▶ MainActivity  [swallowed]
 *      RETURN         ─▶ injected KeyEvent 4 (BACK)                 ─▶ MainActivity  [swallowed]
 *      any            ─▶ notifyValidModeEvt(4098) ─▶ MCU_KEY_INFOR   ─▶ CarEvents     [swallowed]
 *      MODE           ─▶ switchMode()                                                 [NOT ours]
 *      MUTE           ─▶ sendSystemKey(12)                                            [NOT ours]
 *      TALK / VOICE   ─▶ btsuite / CarPlay / startVoice()                             [NOT ours]
 * ```
 *
 * The bottom three act inside the gateway and cannot be intercepted; a long press on those
 * buttons always carries the vendor's short action as collateral. Likewise, with a third-party
 * app (Spotify, CarPlay) in front, the injected KeyEvent goes to that window and lands there.
 *
 * One arm covers one delivery per [Path]: the same release reaches us on both, and a genuine
 * second press must not be eaten by an arm the first delivery already used. Pure; the clock is
 * the caller's.
 */
class WheelKeySwallow {

    /** The two ways a vendor key reaches the launcher. */
    enum class Path { KEY_EVENT, BROADCAST }

    /** Down or up half of an injected KeyEvent pair. */
    enum class Edge { DOWN, UP }

    private data class Arm(val atMs: Long, val used: MutableSet<Path> = HashSet())

    private val arms = HashMap<WheelKey, Arm>()

    /** The up half of a swallowed KeyEvent pair still to drop, if any. */
    private var pendingUp: WheelKey? = null

    /** A long press was acted on for [key]: drop its vendor key for [SWALLOW_WINDOW_MS]. */
    fun arm(key: WheelKey, nowMs: Long) {
        arms[key] = Arm(nowMs)
    }

    /** True when an injected KeyEvent [edge] for [key] is the armed release and must be dropped. */
    fun swallowKeyEvent(key: WheelKey, edge: Edge, nowMs: Long): Boolean {
        if (edge == Edge.UP) {
            val drop = pendingUp == key
            pendingUp = null
            return drop
        }

        val drop = take(key, Path.KEY_EVENT, nowMs)
        pendingUp = if (drop) key else null
        return drop
    }

    /** True when an `MCU_KEY_INFOR` code for [key] is the armed release and must be dropped. */
    fun swallowBroadcast(key: WheelKey, nowMs: Long): Boolean = take(key, Path.BROADCAST, nowMs)

    private fun take(key: WheelKey, path: Path, nowMs: Long): Boolean {
        val arm = arms[key] ?: return false
        if (nowMs - arm.atMs > SWALLOW_WINDOW_MS) {
            arms.remove(key)
            return false
        }
        if (!arm.used.add(path)) {
            return false
        }
        if (arm.used.size == Path.values().size) {
            arms.remove(key)
        }
        return true
    }

    companion object {
        /** The vendor key follows the release by ~100 ms; the margin covers a slow gateway. */
        const val SWALLOW_WINDOW_MS = 1500L
    }
}
