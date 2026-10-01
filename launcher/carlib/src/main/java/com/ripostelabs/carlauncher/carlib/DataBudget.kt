package com.ripostelabs.carlauncher.carlib

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DataBudget: how many bytes the uplink may send over the owner's phone this month.
 *
 * Bluetooth tethering, and Wi-Fi that Android marks metered (a phone hotspot), spend the owner's
 * data plan; home Wi-Fi does not. So only [Link.METERED] is counted, against a monthly limit set
 * in Settings (default [DEFAULT_LIMIT_MB]). Every payload byte is charged at [OVERHEAD] to cover
 * HTTP, TCP and WireGuard framing. The month is the local calendar month and resets on its own.
 */
class DataBudget(
    private val store: Store,
    private val limitBytes: () -> Long,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    enum class Link { NONE, UNMETERED, METERED }

    /** Where usage survives a reboot (SharedPreferences in the app). */
    interface Store {
        var month: String
        var usedBytes: Long
    }

    fun usedBytes(): Long {
        roll()
        return store.usedBytes
    }

    fun allows(link: Link, bytes: Long): Boolean = when (link) {
        Link.NONE -> false
        Link.UNMETERED -> true
        Link.METERED -> usedBytes() + cost(bytes) <= limitBytes()
    }

    fun charge(link: Link, bytes: Long) {
        if (link != Link.METERED) {
            return
        }
        roll()
        store.usedBytes = store.usedBytes + cost(bytes)
    }

    private fun cost(bytes: Long): Long = (bytes * OVERHEAD).toLong()

    private fun roll() {
        val month = MONTH.format(clock().atZone(zone))
        if (store.month == month) {
            return
        }
        store.month = month
        store.usedBytes = 0
    }

    companion object {
        const val DEFAULT_LIMIT_MB = 200L
        const val OVERHEAD = 1.06
        private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    }
}
