package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.AutoAnswer
import com.ripostelabs.carlauncher.carlib.Reconnect
import com.ripostelabs.carlauncher.data.SettingsStore

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
    }
}

private val AUTO_ANSWER_OPTIONS: List<Pair<AutoAnswer, String>> = listOf(
    AutoAnswer.OFF to "Off",
    AutoAnswer.NOW to "At once",
    AutoAnswer.AFTER_3S to "After 3 seconds",
    AutoAnswer.AFTER_5S to "After 5 seconds",
)
