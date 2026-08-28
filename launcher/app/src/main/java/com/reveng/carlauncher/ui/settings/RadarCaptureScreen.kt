package com.reveng.carlauncher.ui.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.RadarCapture
import com.reveng.carlauncher.carlib.RadarState
import com.reveng.carlauncher.data.LauncherSettings
import com.reveng.carlauncher.data.SettingsStore
import com.reveng.carlauncher.ui.collectAsStateSafe
import com.reveng.carlauncher.ui.theme.carShape

/**
 * v2.8 — the raw `MCU_CAR_CAN_RADAR_INFO` frame, byte by byte.
 *
 * **This screen exists because the radar decode cannot be verified from a desk.** The byte layout
 * in [RadarState] was never recovered from the decompile — CAR_API §1.3 says only "byte[] raw radar
 * frame, per-sensor distances" — so every offset and the level polarity are guesses. No amount of
 * reading the vendor code closes that; only a car does. So rather than ship a prettier guess, this
 * ships the instrument: the payload as it arrived, which offsets have moved since the last reset,
 * and what our guess makes of it, side by side on one screen.
 *
 * The capture the user performs (also written up in launcher/README.md):
 *
 *   1. Park. Engage reverse so the MCU starts broadcasting, and open this screen.
 *   2. Press Reset with nothing near the car — that fixes the baseline.
 *   3. Walk an obstacle in toward ONE corner, slowly, and watch which offset sweeps. A byte that
 *      tracks distance climbs (or falls) smoothly; a status flag jumps once and stops.
 *   4. Repeat per corner. The offsets that moved, in the order they moved, *are* the layout.
 *
 * `min`/`max`/`changes` are what make step 3 work without staring: the eye cannot follow eight
 * hex values at 10 Hz, but a byte that has held one value all session is visibly dimmed and a byte
 * with a hundred changes is not.
 *
 * Polarity comes out of the same capture: if the number *falls* as the obstacle approaches, the
 * MCU is sending distance, not a bar count, and [RadarState.proximity] needs inverting.
 *
 * Not gated parked-only. It is a diagnostic that is only useful while the car is stationary with
 * reverse engaged, and the parked-only gate rests on GPS — which does not have a fix in the garage
 * where this capture happens. Gating it would hide the tool in the one place it is used.
 */
@Composable
fun RadarCaptureScreen(
    carEvents: CarEvents,
    settingsStore: SettingsStore,
    onBack: () -> Unit,
) {
    val frame by carEvents.radarRaw.collectAsStateWithLifecycle()
    val settings by settingsStore.settings.collectAsStateSafe(initial = LauncherSettings())

    var capture by remember { mutableStateOf(RadarCapture()) }
    LaunchedEffect(frame) {
        val bytes = frame?.bytes ?: return@LaunchedEffect
        capture = capture.accept(bytes)
    }

    SettingsScaffold(
        title = "Raw frame capture",
        subtitle = "MCU_CAR_CAN_RADAR_INFO · CAR_CAN_DATA",
        onBack = onBack,
    ) {
        SettingsSection(title = "Layout status") {
            Text(
                text = if (settings.radarLayoutConfirmed) {
                    "Marked CONFIRMED. The decode below is trusted and the maneuvering " +
                        "side-strips are allowed to draw."
                } else {
                    "UNCONFIRMED. The byte offsets and the level polarity are guesses that have " +
                        "never been checked against a car, so the maneuvering side-strips stay " +
                        "hidden. The bars elsewhere in Settings still show what arrived — they " +
                        "report the frame, not its meaning."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (settings.radarLayoutConfirmed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.size(12.dp))
            ToggleSetting(
                label = "Layout confirmed on this car",
                description = "Only set this after running the capture below.",
                checked = settings.radarLayoutConfirmed,
                onChange = settingsStore::setRadarLayoutConfirmed,
            )
        }

        SettingsSection(title = "Capture") {
            InfoRow(label = "Frames since reset", value = "${capture.frames}")
            InfoRow(
                label = "Payload length",
                value = if (capture.frames == 0) "—" else "${capture.payloadSize} bytes",
            )
            ActionRow(
                label = "Reset baseline",
                description = "Clears min/max/changes. Press with nothing near the car.",
                onClick = { capture = RadarCapture() },
            )
            ActionRow(
                label = "Write table to logcat",
                description = "Tag $LOG_TAG — for `adb logcat -s $LOG_TAG` alongside the walk.",
                onClick = { dumpToLog(capture) },
                enabled = capture.frames > 0,
            )
        }

        SettingsSection(title = "Bytes") {
            if (capture.frames == 0) {
                Text(
                    text = "Waiting for a frame. The MCU only broadcasts while reversing or at " +
                        "low speed — engage reverse with the car parked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ByteTable(capture = capture)
            }
        }

        SettingsSection(title = "Our decode (GUESSED)") {
            DecodeReadout(bytes = frame?.bytes)
        }
    }
}

/**
 * The per-offset table. A byte that has never moved is drawn on the flat surface colour; one that
 * has is filled and labelled with its span, because the span is the answer being hunted.
 */
@Composable
private fun ByteTable(capture: RadarCapture) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        capture.bytes.chunked(BYTES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { stat ->
                    ByteCell(
                        index = stat.index,
                        value = stat.value,
                        span = if (stat.moved) "${stat.min}–${stat.max}" else "flat",
                        changes = stat.changes,
                        moved = stat.moved,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad a short final row so the cells keep their column width.
                repeat(BYTES_PER_ROW - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ByteCell(
    index: Int,
    value: Int,
    span: String,
    changes: Int,
    moved: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (moved) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val label = if (moved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .clip(carShape(10.dp))
            .background(background)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "[$index]",
            style = MaterialTheme.typography.labelSmall,
            color = label,
        )
        Text(
            text = "%02X".format(value),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = span,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$changes ch",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What [RadarState] currently makes of the live frame. Placed next to the raw bytes on purpose:
 * the moment the two disagree — a sensor the walk proved is moving that decodes as 0, or a level
 * that falls as the obstacle nears — is the moment the layout is disproved.
 */
@Composable
private fun DecodeReadout(bytes: ByteArray?) {
    val decoded = RadarState.fromRadarData(bytes)
    if (!decoded.valid) {
        Text(
            text = "No decode yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    DecodeLine(label = "Front (bytes 1–4)", levels = decoded.front)
    Spacer(Modifier.height(6.dp))
    DecodeLine(label = "Rear (bytes 5–8)", levels = decoded.rear)
    Spacer(Modifier.height(10.dp))
    Text(
        text = "Both rows assume 0 = clear and a higher level = closer, capped at " +
            "${RadarState.LEVEL_MAX}. If the walk makes the numbers fall as the obstacle nears, " +
            "the MCU is sending distance and the ramp needs inverting.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DecodeLine(label: String, levels: List<Int>) {
    val text = if (levels.isEmpty()) "—" else levels.joinToString("  ")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(carShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * One line per offset in logcat, so a walk can be recorded alongside `adb logcat` and read back
 * later. The head unit has no clipboard worth using and the panel is a metre from the driver's
 * phone — a log line is the only export that survives the walk back to a desk.
 */
private fun dumpToLog(capture: RadarCapture) {
    Log.i(LOG_TAG, "frames=${capture.frames} payload=${capture.payloadSize}")
    capture.bytes.forEach { stat ->
        Log.i(
            LOG_TAG,
            "[%02d] cur=%3d min=%3d max=%3d changes=%d".format(
                stat.index, stat.value, stat.min, stat.max, stat.changes,
            ),
        )
    }
}

/** Eight per row mirrors a CAN frame, so an 8-byte payload reads as one line. */
private const val BYTES_PER_ROW = 8

private const val LOG_TAG = "RadarCapture"
