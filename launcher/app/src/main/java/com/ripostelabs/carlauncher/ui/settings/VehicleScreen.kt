package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.service.CanCaptureService
import com.ripostelabs.carlauncher.carlib.VehicleTiles
import kotlinx.coroutines.delay

/**
 * What the car is reporting right now, as tiles.
 *
 * Everything that decides *what* appears lives in [VehicleTiles] and [VehicleState], not here, so
 * it is checked by unit tests rather than by driving. This file only draws.
 *
 * ── Two rules it inherits ───────────────────────────────────────────────────────────────────────
 * A field with no live source contributes nothing — no dash, no zero, no placeholder. An empty
 * list therefore means "no car to talk to" and is rendered as exactly that, because an empty
 * dashboard reads as "everything is fine and zero", which is the worst possible lie for a screen
 * a driver glances at.
 *
 * And no invented thresholds. Nothing here decides a tyre is low or a temperature is high; those
 * numbers have never been established for this vehicle, and a warning built on a plausible guess
 * teaches the driver to ignore warnings.
 */
@Composable
fun VehicleScreen(
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    // The service's snapshot, not a private one: both buses feed it whether or not this screen
    // is open. This page only reads it. One snapshot, two buses.
    val context = LocalContext.current
    val vehicle = remember { CanCaptureService.vehicle() }
    DisposableEffect(Unit) {
        CanCaptureService.start(context)
        onDispose { }
    }

    // MCU frames are folded by MainActivity for the life of the launcher, not here. Folding them
    // here as well would mint every speed-calibration sample twice while this page is open.

    // Staleness expires on read, so the screen has to re-read even when no frame arrives —
    // otherwise a bus that goes silent leaves its last values on screen forever.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(REFRESH_MS)
        }
    }

    val tiles = vehicle.tiles(now)

    SettingsScaffold(
        title = "Vehicle",
        subtitle = "What the car is reporting, live",
        onBack = onBack,
    ) {
        if (tiles.isEmpty()) {
            SettingsSection(title = "No vehicle data") {
                Text(
                    text = "Nothing is reporting. The engine is off, the MCU link is down, or " +
                        "this is not a car. Readings appear as their frames arrive; a field with " +
                        "no live source is left out rather than shown as zero.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@SettingsScaffold
        }

        SettingsSection(title = "Live") {
            tiles.forEach { tile ->
                TileRow(tile)
            }

            Spacer(Modifier.size(8.dp))
            Text(
                text = "Speed is deliberately absent: the candidate fields are uncalibrated, and " +
                    "showing one as fact is how a wrong reading becomes trusted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One tile. ALERT is coloured, and it is reached only for discrete states the car itself reports
 * — an opening ajar, reverse engaged — never for a value compared against a threshold nobody has
 * established for this vehicle.
 */
@Composable
private fun TileRow(tile: VehicleTiles.Tile) {
    SettingRow(label = tile.label) {
        Text(
            text = tile.value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (tile.emphasis == VehicleTiles.Emphasis.ALERT) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Fast enough that a stale value clears while the driver is still looking at it. */
private const val REFRESH_MS = 1_000L
