package com.ripostelabs.carlauncher.carlib

/**
 * DimKey — the panel's DIM key, `ProccessDIMKey` (EventService.java:7909-7942).
 *
 *     72 key 246 ──▶ level of the side the panel shows: ≤6 low, ≤12 mid, else high
 *                ──▶ next step: low → 12, mid → 20, high → 3
 *                ──▶ remember both targets, then one 2E day night 80 200
 *
 * The step goes to the night target while the headlamps are on, else the day one, as the
 * vendor's `mLAMPConnected` branch does (:7914-7935). One deliberate difference: the vendor
 * re-derives its step from the day row alone (initBLLevel, :7947-7957), which at night leaves
 * the key stuck on the low step; here the side being stepped is the one read.
 */
class DimKey(
    private val memory: BacklightMemory,
    private val push: (day: Int, night: Int) -> Unit,
) : McuOwner.Listener {

    override fun onKey(key: Int) {
        if (key != McuOwnerProtocol.Key.DIM) {
            return
        }

        val current = memory.targets()
        val shown = if (memory.lampsOn()) current.night else current.day
        val next = memory.withLevel(nextLevel(shown))

        memory.remember(next.day, next.night)
        push(next.day, next.night)
    }

    /** initBLLevel's three bands (:7950-7956) and the level each band steps to (:7913-7933). */
    private fun nextLevel(level: Int): Int = when {
        level <= LOW_MAX -> MID
        level <= MID_MAX -> HIGH
        else -> LOW
    }

    private companion object {
        const val LOW_MAX = 6
        const val MID_MAX = 12

        const val LOW = 3
        const val MID = 12
        const val HIGH = 20
    }
}
