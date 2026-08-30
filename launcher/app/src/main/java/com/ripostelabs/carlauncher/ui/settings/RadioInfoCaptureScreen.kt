package com.ripostelabs.carlauncher.ui.settings

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ripostelabs.carlauncher.carlib.CanFrame
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.ui.collectAsStateSafe

/**
 * v0.4.3 - the raw radio broadcasts (ZXW_RADIO_INFO_EVT / com.szchoiceway.radio.frequency), for
 * on-device capture. RadioScreen shows only the indicators the 144-method AIDL exposes and cannot
 * show a station name; the action of ZXW_RADIO_INFO_EVT is confirmed but its payload was never
 * traced (README "Known TODOs"). This lists every extra of whichever radio broadcast arrives, by
 * real key name, so what the station name / richer RDS actually rides on is discovered on a tuner.
 *
 * Reuses [CanFrame] (action + all extras) and the shared ExtraRow. No byte accumulator: radio info
 * is expected to be string/int extras, not a distance-tracking byte payload like CAN or radar.
 */
@Composable
fun RadioInfoCaptureScreen(
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val frame by carEvents.radioInfoRaw.collectAsStateSafe(initial = null)
    var last by remember { mutableStateOf<CanFrame?>(null) }
    LaunchedEffect(frame) { frame?.let { last = it } }

    SettingsScaffold(
        title = "Radio info capture",
        subtitle = "ZXW_RADIO_INFO_EVT / radio.frequency - payload UNCONFIRMED",
        onBack = onBack,
    ) {
        SettingsSection(title = "Broadcast") {
            val f = last
            if (f == null) {
                Text(
                    text = "No radio broadcast received yet. Tune the radio on a car; if nothing " +
                        "arrives, the actions are wrong. The action strings are CONFIRMED consts, " +
                        "so a silent tuner points at the payload riding a channel not listed here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                InfoRow(label = "Action", value = f.action.substringAfterLast('.'))
                InfoRow(label = "Extras", value = "${f.extras.size}")
            }
        }

        SettingsSection(title = "Extras (every key, undecoded)") {
            val extras = last?.extras
            if (extras.isNullOrEmpty()) {
                Text(
                    text = "Waiting for a frame. Each extra is shown by its real key name so the " +
                        "one carrying the station name / RDS text can be identified on-device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                extras.forEach { (key, value) -> ExtraRow(key = key, value = value) }
            }
        }

        SettingsSection(title = "Capture") {
            ActionRow(
                label = "Write to logcat",
                description = "Tag $LOG_TAG - for `adb logcat -s $LOG_TAG` while tuning.",
                onClick = { dumpToLog(last) },
                enabled = last != null,
            )
        }
    }
}

private fun dumpToLog(frame: CanFrame?) {
    if (frame == null) return
    Log.i(LOG_TAG, "action=${frame.action}")
    frame.extras.forEach { (k, v) -> Log.i(LOG_TAG, "$k = $v") }
}

private const val LOG_TAG = "RadioInfoCapture"
