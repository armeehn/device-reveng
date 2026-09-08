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
 */
class ReadWatchdog(private val patience: Int = DEFAULT_PATIENCE) {

    private var silent = 0

    /** Record a read. [bytes] is whatever the transfer returned: <= 0 means nothing arrived. */
    fun record(bytes: Int) {
        if (bytes > 0) {
            silent = 0
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
        return true
    }

    private companion object {
        /** At a 200 ms read timeout, 25 silent reads is ~5 s — long enough not to fire on a
         *  momentarily quiet bus, short enough that an unplug is noticed while the screen is
         *  still open. */
        const val DEFAULT_PATIENCE = 25
    }
}
