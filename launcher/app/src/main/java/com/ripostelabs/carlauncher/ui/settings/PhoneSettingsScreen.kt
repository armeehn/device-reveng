package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.AecPath
import com.ripostelabs.carlauncher.carlib.AutoAnswer
import com.ripostelabs.carlauncher.carlib.CallTuning
import com.ripostelabs.carlauncher.carlib.MicGain
import com.ripostelabs.carlauncher.carlib.Reconnect
import com.ripostelabs.carlauncher.data.SettingsStore
import com.ripostelabs.carlauncher.ui.LocalSystemControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RAV4-164 — the phone link's own choices on Riposte OS 0.2 (the car kit, [com.ripostelabs.carlauncher.carlib.BtCarKit]).
 * Stock kept them on btsuite's settings page (auto-answer: `SetUIControllerLandscape.java:270`).
 */
@Composable
fun PhoneSettingsScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit,
) {
    val autoAnswer by settingsStore.autoAnswer.collectAsStateWithLifecycle()
    val reconnect by settingsStore.reconnect.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = "Phone",
        subtitle = "How the car answers and finds the paired phone",
        onBack = onBack,
    ) {
        SettingsSection(title = "Calls") {
            PickerSetting(
                label = "Auto-answer",
                current = autoAnswer,
                options = AUTO_ANSWER_OPTIONS,
                onSelect = { mode -> settingsStore.setAutoAnswer(mode) },
                description = "Answers a ringing phone on its own. Answering or rejecting first cancels it.",
            )
        }
        // RAV4-178: stock's "refuse reconnect", so a second driver's bonded phone does not grab the car.
        SettingsSection(title = "Connection") {
            ToggleSetting(
                label = "Reconnect to the last phone",
                checked = reconnect == Reconnect.LAST_PHONE,
                onChange = { on -> settingsStore.setReconnect(if (on) Reconnect.LAST_PHONE else Reconnect.OFF) },
                description = "At start the car calls the phone it last had. Off: it waits for a phone to connect.",
            )
        }
        CallAudioSection()
    }
}

/**
 * RAV4-184: stock's echo-cancel delays and mic gain, which the BT module's echo canceller reads
 * from props only the system uid may set. So they go through the car service (10); without it
 * the rows show stock's defaults, disabled. Each value is what the service read back on open.
 */
@Composable
private fun CallAudioSection() {
    val scope = rememberCoroutineScope()
    val system = LocalSystemControl.current?.takeIf { it.callAudioRouted }
    var phoneMs by remember { mutableIntStateOf(STOCK_PHONE_DELAY_MS) }
    var carPlayMs by remember { mutableIntStateOf(STOCK_CARPLAY_DELAY_MS) }
    var mic by remember { mutableStateOf(MicGain.G96) }

    LaunchedEffect(system) {
        val s = system ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            s.aecDelay(AecPath.PHONE)?.let { phoneMs = it }
            s.aecDelay(AecPath.CARPLAY)?.let { carPlayMs = it }
            s.micGain()?.let { mic = it }
        }
    }

    // One IPC per release; the row keeps the old value when the service refuses.
    fun delay(path: AecPath, ms: Int, shown: (Int) -> Unit) {
        val s = system ?: return
        scope.launch {
            if (withContext(Dispatchers.IO) { s.setAecDelay(path, ms) }) {
                shown(ms)
            }
        }
    }

    SettingsSection(title = "Call audio") {
        SliderSetting(
            label = "Echo delay, phone calls",
            value = phoneMs,
            range = 0..CallTuning.MAX_DELAY_MS,
            step = CallTuning.DELAY_STEP_MS,
            enabled = system != null,
            description = "Change it if callers hear their own voice back",
            format = { "$it ms" },
            onChange = { ms -> delay(AecPath.PHONE, ms) { phoneMs = it } },
        )
        SliderSetting(
            label = "Echo delay, CarPlay calls",
            value = carPlayMs,
            range = 0..CallTuning.MAX_DELAY_MS,
            step = CallTuning.DELAY_STEP_MS,
            enabled = system != null,
            format = { "$it ms" },
            onChange = { ms -> delay(AecPath.CARPLAY, ms) { carPlayMs = it } },
        )
        PickerSetting(
            label = "Microphone gain",
            current = mic,
            options = MicGain.entries.map { it to it.value.toString() },
            enabled = system != null,
            description = "Raise it if callers say the car is quiet",
            onSelect = { gain ->
                val s = system ?: return@PickerSetting
                scope.launch {
                    if (withContext(Dispatchers.IO) { s.setMicGain(gain) }) {
                        mic = gain
                    }
                }
            },
        )
        if (system == null) {
            Text(
                text = "Set through the car service on Riposte OS 0.2. This unit has none, so the " +
                    "rows show stock's defaults.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Stock eventcenter's defaults (EventService.java:6693-6694); the mic default is 96. */
private const val STOCK_PHONE_DELAY_MS = 10
private const val STOCK_CARPLAY_DELAY_MS = 0

private val AUTO_ANSWER_OPTIONS: List<Pair<AutoAnswer, String>> = listOf(
    AutoAnswer.OFF to "Off",
    AutoAnswer.NOW to "At once",
    AutoAnswer.AFTER_3S to "After 3 seconds",
    AutoAnswer.AFTER_5S to "After 5 seconds",
)
