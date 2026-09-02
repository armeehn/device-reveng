package com.ripostelabs.carlauncher.carlib

/**
 * v0.4.3.8 — rejects a protected car event that has already been applied.
 *
 * On a rooted unit the same `STEER_WHEEL_INFOR` can reach the launcher twice: once through the
 * in-process [android.content.BroadcastReceiver] (a privileged/system install receives the
 * protected actions directly) and once through the root helper. Applying it twice is a second key
 * press as far as the focus ring is concerned, so one wheel press moves the ring two steps.
 *
 * v2.9 guarded that with `if (!rootCapture.value)` in the receiver. That is a check-then-act
 * across two threads — the flag is published from the root reader thread while the test runs on
 * the main thread — so an event arriving in that window is still handled twice. This dedupes on
 * the **event** instead, which does not care which thread saw it first:
 *
 * ```
 *   receiver  ──┐
 *               ├─► accept(action, ints, atMs) ─► handleProtected()   (first arrival only)
 *   root path ──┘
 * ```
 *
 * **Only the immediately preceding event is compared**, not a per-key history. Two paths carrying
 * one event deliver it back to back with nothing in between, so adjacency catches them; a genuine
 * fast double-press is `down, up, down` and its second `down` is preceded by an `up`, a different
 * event, so it is never swallowed however fast the driver taps.
 *
 * A rejected arrival does not slide the window either: it stays anchored to the first arrival, so
 * suppression can never chain past [WINDOW_MS] no matter how many copies turn up.
 */
internal class ProtectedEventDedupe(private val windowMs: Long = WINDOW_MS) {

    companion object {
        /**
         * How close two identical events must be to be treated as one delivery.
         *
         * The two paths differ only by a pipe read and a thread hop, which is single-digit
         * milliseconds; this is an order of magnitude of headroom on that. It is well under the
         * ~250 ms a deliberate double-tap takes, and held-repeat is generated locally from the
         * press/release edges rather than from repeated broadcasts, so nothing legitimate repeats
         * faster than this.
         */
        const val WINDOW_MS = 120L
    }

    private var lastAction: String? = null
    private var lastInts: Map<String, Int> = emptyMap()
    private var lastAtMs = 0L

    /**
     * @return true if this arrival should be applied, false if it is a duplicate of the previous
     *   one within [windowMs]. Synchronized: the two callers are on different threads.
     */
    @Synchronized
    fun accept(action: String, ints: Map<String, Int>, atMs: Long): Boolean {
        val canonicalInts = canonical(ints)
        val elapsed = atMs - lastAtMs
        val duplicate = action == lastAction &&
            canonicalInts == lastInts &&
            elapsed >= 0 &&
            elapsed < windowMs

        if (duplicate) {
            return false
        }

        lastAction = action
        lastInts = canonicalInts
        lastAtMs = atMs
        return true
    }

    /**
     * v0.4.7 — the two delivery paths build unequal maps for the same event: the in-process
     * receiver fills an absent extra with 0 while the root helper skips it, so plain map equality
     * let the same press through twice. An absent extra and one delivered as 0 decode identically
     * downstream (handleProtected defaults missing to 0), so dropping zero entries compares the
     * event, not the carrier.
     */
    private fun canonical(ints: Map<String, Int>): Map<String, Int> =
        ints.filterValues { it != 0 }
}
