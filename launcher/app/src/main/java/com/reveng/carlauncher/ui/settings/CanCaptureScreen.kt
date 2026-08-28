package com.reveng.carlauncher.ui.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.carlib.CanFrame
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.RadarCapture
import com.reveng.carlauncher.ui.collectAsStateSafe
import com.reveng.carlauncher.ui.theme.carShape

/**
 * v0.4.3 — the raw CAN bulk frame (`CAN_BASIC_EVT` / `MCU_CAR_CAN_INFO`), for on-device capture.
 *
 * Decoding this frame is the standing upgrade to the GPS-only speed source (README "Known TODOs";
 * it is available instantly at power-on and indoors, where GPS is not). Two things are unknown from
 * a desk and only a car settles them: **which action actually arrives** (the fully-qualified
 * strings are GUESSED at the vendor's `EventUtils.*` prefix) and **which extra carries the
 * payload** (never quoted in the decompile). So this ships the instrument, mirroring the v2.8 radar
 * capture: every extra of each frame is listed by name, and any `byte[]` payload runs through the
 * same [RadarCapture] min/max/change accumulator so the byte that tracks speed reveals itself when
 * the car is driven.
 *
 * The capture to perform:
 *   1. Open this screen with the engine running (frames should arrive at power-on, no reverse
 *      needed — unlike radar).
 *   2. Note which **action** and **extra key** appear. If nothing appears at all, the action
 *      strings are wrong and need re-deriving from the decompile.
 *   3. Press Reset while stationary, then drive slowly. Exactly the byte(s) encoding speed climb
 *      with the car; a gear/handbrake flag jumps once. `min`/`max`/`changes` separate them.
 *   4. "Write to logcat" records it for `adb logcat -s CanCapture` on the drive back.
 *
 * Not gated parked-only: like the radar capture it is a stationary-then-slow diagnostic, and the
 * parked gate rests on a GPS fix this frame exists to replace.
 */
@Composable
fun CanCaptureScreen(
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val frame by carEvents.canRaw.collectAsStateSafe(initial = null)

    var capture by remember { mutableStateOf(RadarCapture()) }
    var lastFrame by remember { mutableStateOf<CanFrame?>(null) }
    LaunchedEffect(frame) {
        val f = frame ?: return@LaunchedEffect
        lastFrame = f
        val bytes = f.bytes ?: return@LaunchedEffect
        capture = capture.accept(bytes)
    }

    SettingsScaffold(
        title = "CAN frame capture",
        subtitle = "CAN_BASIC_EVT / MCU_CAR_CAN_INFO — action & payload UNCONFIRMED",
        onBack = onBack,
    ) {
        SettingsSection(title = "Broadcast") {
            val f = lastFrame
            if (f == null) {
                Text(
                    text = "No CAN frame received yet. The action strings are GUESSED at the " +
                        "EventUtils.* prefix — if nothing arrives on a running car, they are wrong " +
                        "and must be re-derived from the decompile (EvtModel.java).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                InfoRow(label = "Action", value = f.action.substringAfterLast('.'))
                InfoRow(label = "Extras", value = "${f.extras.size}")
                InfoRow(
                    label = "Payload",
                    value = f.bytes?.let { "byte[${it.size}]" } ?: "none found",
                )
            }
        }

        SettingsSection(title = "Extras (every key, undecoded)") {
            val extras = lastFrame?.extras
            if (extras.isNullOrEmpty()) {
                Text(
                    text = "Waiting for a frame. Each extra is shown by its real key name so the " +
                        "one carrying the payload can be identified on-device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                extras.forEach { (key, value) -> ExtraRow(key = key, value = value) }
            }
        }

        SettingsSection(title = "Payload bytes") {
            InfoRow(label = "Frames since reset", value = "${capture.frames}")
            InfoRow(
                label = "Payload length",
                value = if (capture.frames == 0) "—" else "${capture.payloadSize} bytes",
            )
            ActionRow(
                label = "Reset baseline",
                description = "Clears min/max/changes. Press while stationary before driving.",
                onClick = { capture = RadarCapture() },
            )
            ActionRow(
                label = "Write table to logcat",
                description = "Tag $LOG_TAG — for `adb logcat -s $LOG_TAG` alongside the drive.",
                onClick = { dumpToLog(lastFrame, capture) },
                enabled = capture.frames > 0,
            )
            Spacer(Modifier.size(8.dp))
            if (capture.frames == 0) {
                Text(
                    text = "No byte[] payload accumulated yet — either no frame has arrived or the " +
                        "payload rides an extra that isn't a byte array (check the Extras list).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ByteTable(capture = capture)
            }
        }
    }
}

@Composable
internal fun ExtraRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1.4f)
                .clip(carShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/** One header line + one line per offset in logcat, to record a drive for reading back at a desk. */
private fun dumpToLog(frame: CanFrame?, capture: RadarCapture) {
    Log.i(LOG_TAG, "action=${frame?.action} extras=${frame?.extras?.keys} frames=${capture.frames} payload=${capture.payloadSize}")
    capture.bytes.forEach { stat ->
        Log.i(
            LOG_TAG,
            "[%02d] cur=%3d min=%3d max=%3d changes=%d".format(
                stat.index, stat.value, stat.min, stat.max, stat.changes,
            ),
        )
    }
}

private const val LOG_TAG = "CanCapture"
