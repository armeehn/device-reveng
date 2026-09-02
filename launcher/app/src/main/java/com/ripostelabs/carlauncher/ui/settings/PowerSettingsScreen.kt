package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.SettingKeys

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
            GuessedRangeSlider(
                controller = controller,
                label = "Power-on delay",
                description = "Wait before the unit wakes after ACC on",
                key = SettingKeys.ACC_ON_DELAY,
                default = 0,
                range = 0..30,
                format = { "${it}s" },
            )
            GuessedRangeSlider(
                controller = controller,
                label = "Power-off delay",
                description = "Keep running after ACC off",
                key = SettingKeys.ACC_OFF_DELAY,
                default = 0,
                range = 0..30,
                format = { "${it}s" },
            )
            GuessedRangeSlider(
                controller = controller,
                label = "Full power-off delay",
                description = "Delay before a full shutdown",
                key = SettingKeys.POWER_OFF_DELAY,
                default = 0,
                range = 0..60,
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
            GuessedRangeSlider(
                controller = controller,
                label = "Sleep after",
                description = "Idle time before sleeping",
                key = SettingKeys.SLEEP_TIME,
                default = 10,
                range = 1..60,
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

/**
 * A slider over a SysVar whose range is a guess (see the header ⚠). A vendor value outside the
 * declared range proves the guess wrong for this unit — a plain slider would coerce it into
 * range and commit the coerced value on the first touch, destroying the original. Such a value
 * is rendered read-only, raw, until the range is confirmed on-device. A missing key falls back
 * to [default] and stays adjustable.
 */
@Composable
private fun GuessedRangeSlider(
    controller: CarSettingsController,
    label: String,
    description: String,
    key: String,
    default: Int,
    range: IntRange,
    format: (Int) -> String,
    enabled: Boolean = true,
) {
    val raw = controller.getString(key)
    val value = if (raw.isBlank()) default else raw.trim().toIntOrNull()

    if (value == null || value !in range) {
        InfoRow(label = label, value = "$raw — read-only, outside the expected range")
        return
    }

    SliderSetting(
        label = label,
        description = description,
        value = value,
        range = range,
        onChange = { controller.setInt(key, it) },
        enabled = enabled,
        format = format,
    )
}
