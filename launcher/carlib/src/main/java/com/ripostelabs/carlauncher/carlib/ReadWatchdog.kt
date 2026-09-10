package com.ripostelabs.carlauncher.carlib

/**
 * ReadWatchdog — when a run of empty reads is worth suspecting the link rather than the bus.
 *
 * A bulk read that returns nothing is ambiguous. On a parked car the bus really is quiet, and on
 * an unplugged adapter it is quiet forever. Observed 2026-09-08: pulling the CANable mid-session
 * left the reader spinning on a dead handle, still publishing the last good state, because nothing
 * ever asked whether the device was still there.
 *
 * The rule is deliberately not "reconnect after N silent reads" — that would drop a working link
 * every time the car sat still. It is "after N silent reads, go and CHECK". The check is cheap and
 * its answer is unambiguous, which the silence never is.
 *
 * ── The third state, added 2026-09-10 ───────────────────────────────────────────────────────────
 * Checking whether the device is attached answers two cases and misses a third. The adapter can
 * stay enumerated, keep its handle valid, and simply stop delivering. The owner's report is the
 * clearest description of it: unplugging the adapter and plugging it back in makes it work again.
 * A physical replug forces a fresh claim and a fresh channel open, so the fault survives the
 * handle rather than the device.
 *
 * Against that, "is it still there" always answers yes and the reader spins on a dead pipe
 * forever. So silence that outlives several attachment checks escalates to [shouldReclaim], and
 * the caller tears the session down and opens it again — the same thing the hand does, in
 * software, without anybody reaching behind the dash.
 */
class ReadWatchdog(
    private val patience: Int = DEFAULT_PATIENCE,
    private val reclaimAfter: Int = DEFAULT_RECLAIM_AFTER,
) {

    private var silent = 0
    private var silentVerifications = 0

    /** Record a read. [bytes] is whatever the transfer returned: <= 0 means nothing arrived. */
    fun record(bytes: Int) {
        if (bytes > 0) {
            silent = 0
            silentVerifications = 0
            return
        }

        silent++
    }

    /**
     * Whether enough consecutive silence has built up to justify checking the device is attached.
     * Asking resets the count, so a still-attached adapter is re-checked every [patience] reads
     * rather than on every read once the threshold is crossed.
     */
    fun shouldVerifyDevice(): Boolean {
        if (silent < patience) {
            return false
        }

        silent = 0
        silentVerifications++
        return true
    }

    /**
     * Whether the link has been silent across enough attachment checks to stop trusting the
     * handle and open a new one.
     *
     * Only ever true after [shouldVerifyDevice] has fired repeatedly, so it cannot pre-empt the
     * cheaper question. The caller adds the condition this class cannot see: that the session had
     * been delivering frames before it went quiet. A link that never worked is not stalled, and
     * reopening it would achieve nothing but a loop.
     */
    fun shouldReclaim(): Boolean = silentVerifications >= reclaimAfter

    private companion object {
        /** At a 200 ms read timeout, 25 silent reads is ~5 s — long enough not to fire on a
         *  momentarily quiet bus, short enough that an unplug is noticed while the screen is
         *  still open. */
        const val DEFAULT_PATIENCE = 25

        /** Three attachment checks is ~15 s of silence. A live bus runs at over a thousand
         *  frames a second, so fifteen silent seconds on a link that was working is not a quiet
         *  moment; it is a link that has stopped. */
        const val DEFAULT_RECLAIM_AFTER = 3
    }
}
