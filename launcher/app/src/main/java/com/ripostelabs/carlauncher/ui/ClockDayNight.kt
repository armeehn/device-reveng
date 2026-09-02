package com.ripostelabs.carlauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * v2.7 — the clock-based day/night fallback.
 *
 * The car tells us about illumination over LAMP_STATUS, which the gateway only sends when the
 * headlamps actually toggle, so a session can pass without one. The unit then sits in day
 * colours at midnight, which is the one situation a head-unit theme genuinely must not be in.
 *
 * This is the crude answer, and crude on purpose: two hours, no solar maths. Real civil twilight
 * needs a date and a position, and the launcher's position comes from the same GPS that the v2.5
 * motion gate has to fail open around — no fix in a garage, none at power-on, and none at all
 * without the location grant. A calculation that is wrong exactly when it is needed is worse than
 * a window the driver set themselves.
 */
@Composable
fun rememberClockNight(startHour: Int, endHour: Int): State<Boolean> =
    produceState(initialValue = isNightAt(currentHour(), startHour, endHour), startHour, endHour) {
        while (true) {
            value = isNightAt(currentHour(), startHour, endHour)
            // Re-check on the minute rather than on the hour: a tick aligned to the wall clock
            // costs nothing and means the switch happens when the driver expects it, not up to an
            // hour late because the launcher happened to start at 18:59.
            delay(TICK_MS)
        }
    }

private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

/**
 * True when [hour] falls inside the night window.
 *
 * The window normally wraps midnight (19:00 → 07:00), so the two cases are genuinely different:
 * wrapped means "at or after start OR before end", un-wrapped (someone who works nights and sets
 * 07:00 → 19:00) means "at or after start AND before end". A single expression covering both is
 * where this kind of code goes wrong.
 */
internal fun isNightAt(hour: Int, startHour: Int, endHour: Int): Boolean {
    if (startHour == endHour) {
        // An empty window would mean "never night", which is a strange thing to have configured
        // and almost certainly a mis-set slider. Treat it as always night: the failure mode of a
        // too-dark screen is a driver reaching for the setting, not one who cannot see the road.
        return true
    }
    if (startHour > endHour) {
        return hour >= startHour || hour < endHour
    }
    return hour in startHour until endHour
}

/** One minute. The status bar clock already ticks at 1 s; day/night does not need that. */
private const val TICK_MS = 60_000L
