package com.ripostelabs.carlauncher.ui.settings

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.McuDiagnostics
import com.ripostelabs.carlauncher.carlib.McuOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Riposte OS 0.2: the MCU link as the owner sees it, live. Stands in for the vendor's
 * canbusdebug floating window (raw relay hex, decoded state, save-to-file): the numbers and lines
 * come from [McuDiagnostics], so what is on screen is what "Copy to log" writes.
 */
@Composable
fun DiagnosticsScreen(
    diagnostics: McuDiagnostics?,
    mcuStatus: StateFlow<McuOwner.Status>?,
    onBack: () -> Unit,
) {
    // Ages expire on read, so the page re-reads on a clock even when no frame arrives.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(REFRESH_MS)
        }
    }

    SettingsScaffold(
        title = "Diagnostics",
        subtitle = "MCU link, raw CAN relay and decoded signals, live",
        onBack = onBack,
    ) {
        if (diagnostics == null || mcuStatus == null) {
            SettingsSection(title = "Vendor slot") {
                Text(
                    text = "The MCU port belongs to the vendor stack on this build; there is nothing to watch here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@SettingsScaffold
        }

        val status by mcuStatus.collectAsStateWithLifecycle()
        val snapshot by diagnostics.snapshot.collectAsStateWithLifecycle()
        val car = snapshot.canBoxCar
        val toldAt = snapshot.canBoxToldAtMs

        SettingsSection(title = "MCU link") {
            InfoRow(label = "Link", value = McuDiagnostics.Format.status(status))
            InfoRow(label = "MCU version", value = snapshot.mcuVersion ?: "unknown")
            InfoRow(
                label = "CAN box car",
                value = if (car == null || toldAt == null) {
                    "not told yet"
                } else {
                    "${car.label} (0x%02X), ${McuDiagnostics.Format.age(toldAt, now)} ago".format(car.carType)
                },
            )
            InfoRow(
                label = "Relays",
                value = "${snapshot.relayCount} bodies, ${snapshot.boxFrames} frames, ${snapshot.boxMalformed} malformed",
            )
            ActionRow(
                label = "Copy to log",
                description = "Tag $LOG_TAG, for `rav4 car diag` and `adb logcat -s $LOG_TAG`.",
                onClick = { diagnostics.report(status).forEach { Log.i(LOG_TAG, it) } },
            )
        }

        SettingsSection(title = "CAN signals") {
            if (snapshot.signals.isEmpty()) {
                Muted("No box frame decoded yet.")
            }
            snapshot.signals.forEach { s ->
                InfoRow(label = McuDiagnostics.Format.age(s.atMs, now), value = McuDiagnostics.Format.signal(s.signal))
            }
        }

        SettingsSection(title = "Raw relay") {
            if (snapshot.relays.isEmpty()) {
                Muted("No 0xA5 relay from the MCU yet.")
            }
            Column(Modifier.fillMaxWidth()) {
                // Newest at the top: that is what a driver watching a key press wants to see first.
                McuDiagnostics.Format.relayLines(snapshot.relays, now).asReversed().forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val LOG_TAG = "McuDiag"

/** Fast enough that an age reads as a clock, slow enough not to fight the relay stream. */
private const val REFRESH_MS = 1_000L
