package com.ripostelabs.carlauncher.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.ripostelabs.carlauncher.data.SunNight
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * v2.7 — the clock-based day/night fallback.
 *
 * The car tells us about illumination over LAMP_STATUS, which the gateway only sends when the
 * headlamps actually toggle, so a session can pass without one. The unit then sits in day
 * colours at midnight, which is the one situation a head-unit theme genuinely must not be in.
 *
 * RAV4-169: sunrise and sunset from the last GPS fix come first ([SunNight]), as stock's
 * SunTimesUtil does. The two hours the driver set are the answer only when no fix was ever
 * seen: no location grant, or a unit that has never had the sky.
 */
@Composable
fun rememberClockNight(startHour: Int, endHour: Int): State<Boolean> {
    val context = LocalContext.current
    return produceState(initialValue = clockNight(context, startHour, endHour), startHour, endHour) {
        while (true) {
            value = clockNight(context, startHour, endHour)
            // Re-check on the minute rather than on the hour: a tick aligned to the wall clock
            // costs nothing and means the switch happens when the driver expects it, not up to an
            // hour late because the launcher happened to start at 18:59.
            delay(TICK_MS)
        }
    }
}

private fun clockNight(context: Context, startHour: Int, endHour: Int): Boolean =
    SunNight.isNight(context, System.currentTimeMillis()) ?: isNightAt(currentHour(), startHour, endHour)

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
