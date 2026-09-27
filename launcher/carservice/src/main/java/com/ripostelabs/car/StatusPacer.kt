package com.ripostelabs.car

import com.ripostelabs.carlauncher.carlib.McuOwner

/**
 * Which owner statuses go out to clients. The owner bumps its frame counters on every read,
 * dozens a second while the CAN box talks; a client needs each state change at once and the
 * counters only now and then. So: a change of state, ACK or reason goes out, counters at most
 * every [gapMs].
 */
class StatusPacer(private val gapMs: Long = GAP_MS) {

    private var last: McuOwner.Status? = null
    private var lastAtMs = 0L

    /** True when [status], seen at [nowMs], should be published; it then becomes the last. */
    fun due(status: McuOwner.Status, nowMs: Long): Boolean {
        val prev = last
        val send = prev == null || !sameState(prev, status) || nowMs - lastAtMs >= gapMs
        if (send) {
            last = status
            lastAtMs = nowMs
        }
        return send
    }

    // Running with only the counters moved is the same state; anything else is a change.
    private fun sameState(a: McuOwner.Status, b: McuOwner.Status): Boolean {
        if (a is McuOwner.Status.Running && b is McuOwner.Status.Running) {
            return a.acked == b.acked
        }
        return a == b
    }

    private companion object {
        const val GAP_MS = 1_000L
    }
}
