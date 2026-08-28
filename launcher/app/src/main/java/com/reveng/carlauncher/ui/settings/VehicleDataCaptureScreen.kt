package com.reveng.carlauncher.ui.settings

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.reveng.carlauncher.carlib.CanFrame
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.ui.collectAsStateSafe

/**
 * v0.4.3 - a generic sniffer over the cluster of confirmed-const CAN events whose payloads were
 * never decoded (CAR_API line 111). One screen captures every extra of each: to pin the key names
 * v3.0 guesses (outside temp, steering) and open the ones with no reader at all (TPMS, seat, fuel,
 * trip computer, centre console). Reuses CanFrame + the shared ExtraRow. Nothing arrives off a car.
 */
@Composable
fun VehicleDataCaptureScreen(
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val sniff by carEvents.vehicleSniff.collectAsStateSafe(initial = emptyMap())
    val seen = sniff.size

    SettingsScaffold(
        title = "Vehicle data capture",
        subtitle = "8 confirmed CAN events - payloads UNCONFIRMED (" + seen + " seen)",
        onBack = onBack,
    ) {
        SettingsSection(title = "About") {
            Text(
                text = "Each row is a CAN event the vendor confirms it broadcasts but whose " +
                    "payload was never decoded. Drive/interact with the car; whichever arrives " +
                    "lists its extras by real key name so the data can be located. Nothing off a car.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        EVENTS.forEach { pair ->
            SettingsSection(title = pair.second) {
                val frame = sniff[pair.first]
                if (frame == null) {
                    Text(
                        text = "Not seen yet (" + pair.first.substringAfterLast(".") + ")",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    frame.extras.forEach { entry -> ExtraRow(key = entry.key, value = entry.value) }
                }
            }
        }

        SettingsSection(title = "Capture") {
            ActionRow(
                label = "Write all to logcat",
                description = "Tag " + LOG_TAG + " - for adb logcat -s " + LOG_TAG + " while driving.",
                onClick = { dumpToLog(sniff) },
                enabled = sniff.isNotEmpty(),
            )
        }
    }
}

private val EVENTS: List<Pair<String, String>> = listOf(
    CarEvents.CAN_CAR_TIRP_INFO to "Trip computer",
    CarEvents.CAN_FUEL_CONSUMPTION_INFOR to "Fuel consumption",
    CarEvents.CAN_TPMS_DATA_EVT to "Tyre pressure (TPMS)",
    CarEvents.CAN_SEAT_DATA_EVT to "Seat",
    CarEvents.CAN_CENTER_CONSOLE_INFOR to "Centre console",
    CarEvents.CAN_SLS_DATA_EVT to "SLS",
    CarEvents.CAN_CAR_OUT_SIDE_TEMP_EVT to "Outside temperature",
    CarEvents.ZXW_CAN_WHEEL_TRACK_EVT to "Steering angle",
)

private fun dumpToLog(sniff: Map<String, CanFrame>) {
    sniff.forEach { entry ->
        Log.i(LOG_TAG, "== " + entry.key + " ==")
        entry.value.extras.forEach { e -> Log.i(LOG_TAG, e.key + " = " + e.value) }
    }
}

private const val LOG_TAG = "VehicleCapture"
