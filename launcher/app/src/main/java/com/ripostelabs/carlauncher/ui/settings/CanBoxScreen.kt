package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CarProfiles
import com.ripostelabs.carlauncher.data.SettingsStore

/**
 * Which car the CAN box is told it is in. The box needs the right car-type byte before it
 * relays the car's frames (inferred from canbus2, unproven in the car); a change is sent at once.
 */
@Composable
fun CanBoxScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit,
) {
    val settings by settingsStore.settings.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = "CAN box",
        subtitle = "Which car the CAN box is told it is in",
        onBack = onBack,
    ) {
        SettingsSection(title = "Car") {
            PickerSetting(
                label = "Car",
                current = CarProfiles.byId(settings.canBoxCar).id,
                options = CarProfiles.ALL.map { it.id to it.label },
                onSelect = { id -> settingsStore.setCanBoxCar(id) },
                description = "HiWorld box, Toyota and Lexus. The table comes from the stock canbus2 app.",
            )
        }
    }
}
