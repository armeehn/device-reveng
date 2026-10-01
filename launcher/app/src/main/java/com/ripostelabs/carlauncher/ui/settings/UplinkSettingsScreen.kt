package com.ripostelabs.carlauncher.ui.settings

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.NoisePlan
import com.ripostelabs.carlauncher.data.UplinkPrefs
import java.util.Locale

/**
 * Settings > Improve noise reduction: the owner's switch for automatic road-noise capture, the
 * monthly tether budget, and what the uplink has done (UplinkService).
 */
@Composable
fun UplinkSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { UplinkPrefs.get(context) }
    val status by prefs.status.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = "Improve noise reduction",
        subtitle = "Road noise from your drives trains the microphone's noise filter",
        onBack = onBack,
    ) {
        SettingsSection {
            ToggleSetting(
                label = "Improve noise reduction automatically",
                checked = status.autoCapture == NoisePlan.Switch.ON,
                onChange = { on -> prefs.setAutoCapture(if (on) NoisePlan.Switch.ON else NoisePlan.Switch.OFF) },
                description = "While you drive, the unit records short clips of cabin noise. A clip " +
                    "with any speech in it is deleted on the unit and never sent. Never during calls, " +
                    "CarPlay, Siri or reversing.",
            )
            PickerSetting(
                label = "Phone data per month",
                current = status.budgetMb,
                options = UplinkPrefs.BUDGET_CHOICES.map { it to if (it == 0L) "Wi-Fi only" else "$it MB" },
                onSelect = prefs::setBudgetMb,
                description = "Uploads over Bluetooth tethering or a phone hotspot stop at this. Home Wi-Fi is not counted.",
            )
        }
        SettingsSection(title = "Status") {
            InfoRow("Now", status.state)
            InfoRow("Last upload", lastUpload(status.lastUploadMs))
            InfoRow("Waiting to upload", mb(status.queuedBytes))
            InfoRow("Phone data this month", "${mb(status.usedBytes)} of ${status.budgetMb} MB")
            InfoRow("Road noise kept", seconds(status.keptSeconds))
            InfoRow("Speech discarded", seconds(status.droppedSpeechSeconds))
            InfoRow("Files uploaded", status.uploadedFiles.toString())
        }
    }
}

private fun lastUpload(ms: Long): String =
    if (ms == 0L) "Never" else DateUtils.getRelativeTimeSpanString(ms).toString()

private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / UplinkPrefs.MB.toDouble())

private fun seconds(s: Double): String = if (s < 60) "${s.toInt()} s" else "${(s / 60).toInt()} min"
