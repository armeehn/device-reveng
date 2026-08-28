package com.reveng.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.SettingKeys

/**
 * v1.9 — Power & sleep. Mirrors the vendor ACC/sleep timing page, reskinned, with a live ACC
 * status readout from [CarEvents.accOn]. Timing values are SysVar-backed (CAR_API §2.3).
 *
 * ⚠ Delay/sleep ranges are inferred from key naming; the vendor stores raw seconds/minutes.
 * Verify the units on-device and adjust the slider bounds if needed.
 */
@Composable
fun PowerSettingsScreen(
    controller: CarSettingsController,
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    snap
    val accOn by carEvents.accOn.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Power & sleep", onBack = onBack) {
        SettingsSection(title = "Status") {
            InfoRow(label = "ACC (ignition)", value = if (accOn) "On" else "Off")
        }

        SettingsSection(title = "ACC power delays") {
            SliderSetting(
                label = "Power-on delay",
                description = "Wait before the unit wakes after ACC on",
                value = controller.getInt(SettingKeys.ACC_ON_DELAY, 0),
                range = 0..30,
                onChange = { controller.setInt(SettingKeys.ACC_ON_DELAY, it) },
                format = { "${it}s" },
            )
            SliderSetting(
                label = "Power-off delay",
                description = "Keep running after ACC off",
                value = controller.getInt(SettingKeys.ACC_OFF_DELAY, 0),
                range = 0..30,
                onChange = { controller.setInt(SettingKeys.ACC_OFF_DELAY, it) },
                format = { "${it}s" },
            )
            SliderSetting(
                label = "Full power-off delay",
                description = "Delay before a full shutdown",
                value = controller.getInt(SettingKeys.POWER_OFF_DELAY, 0),
                range = 0..60,
                onChange = { controller.setInt(SettingKeys.POWER_OFF_DELAY, it) },
                format = { "${it}s" },
            )
        }

        SettingsSection(title = "Sleep") {
            ToggleSetting(
                label = "Enable sleep",
                description = "Let the unit sleep instead of powering off",
                checked = controller.getBoolean(SettingKeys.SLEEP_SWITCH, false),
                onChange = { controller.setBoolean(SettingKeys.SLEEP_SWITCH, it) },
            )
            SliderSetting(
                label = "Sleep after",
                description = "Idle time before sleeping",
                value = controller.getInt(SettingKeys.SLEEP_TIME, 10),
                range = 1..60,
                onChange = { controller.setInt(SettingKeys.SLEEP_TIME, it) },
                enabled = controller.getBoolean(SettingKeys.SLEEP_SWITCH, false),
                format = { "${it} min" },
            )
        }

        Text(
            text = "Timing units are inferred from the vendor firmware; confirm seconds vs " +
                "minutes on-device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
