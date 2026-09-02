package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.PowerOptions
import com.ripostelabs.carlauncher.data.SettingKeys

/**
 * v1.9 — Power & sleep. Mirrors the vendor ACC/sleep timing page, reskinned, with a live ACC
 * status readout from [CarEvents.accOn]. Values are SysVar-backed (CAR_API §2.3); their domains
 * come from the decompiled vendor settings app and live in [PowerOptions].
 *
 * The ACC-off delay key (`ACC_OFF_DELAY`) is deliberately absent: the gateway never reads it.
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
    val closeScreenActive =
        controller.getInt(SettingKeys.CUSTOMER_TYPE) == PowerOptions.CLOSE_SCREEN_CUSTOMER_TYPE

    SettingsScaffold(title = "Power & sleep", onBack = onBack) {
        SettingsSection(title = "Status") {
            InfoRow(label = "ACC (ignition)", value = if (accOn) "On" else "Off")
        }

        SettingsSection(title = "ACC power delays") {
            RangeSetting(
                controller = controller,
                label = "Power-on delay",
                description = "Wait before the unit wakes after ACC on",
                key = SettingKeys.ACC_ON_DELAY,
                default = PowerOptions.ACC_ON_DELAY_SECONDS.first,
                range = PowerOptions.ACC_ON_DELAY_SECONDS,
                format = { "${it}s" },
            )
            RangeSetting(
                controller = controller,
                label = "ACC delay",
                description = "Sent to the MCU as minutes:seconds; the ceiling is ours, not the vendor's",
                key = SettingKeys.ACC_DELAY,
                default = PowerOptions.ACC_DELAY_SECONDS.first,
                range = PowerOptions.ACC_DELAY_SECONDS,
                format = PowerOptions::minutesSeconds,
            )
            ToggleSetting(
                label = "ACC off delay",
                description = "Factory flag: keep running briefly after ACC off",
                checked = controller.getBoolean(SettingKeys.POWER_OFF_DELAY, false),
                onChange = { controller.setBoolean(SettingKeys.POWER_OFF_DELAY, it) },
            )
            ToggleSetting(
                label = "Screen off with ACC",
                description = "Blank the screen when ACC changes",
                checked = controller.getBoolean(SettingKeys.SCREEN_OFF_WHEN_ACC_CHANGE, true),
                onChange = { controller.setBoolean(SettingKeys.SCREEN_OFF_WHEN_ACC_CHANGE, it) },
            )
        }

        SettingsSection(title = "Sleep") {
            ToggleSetting(
                label = "Enable sleep",
                description = "Let the unit sleep instead of powering off",
                checked = controller.getBoolean(SettingKeys.SLEEP_SWITCH, false),
                onChange = { controller.setBoolean(SettingKeys.SLEEP_SWITCH, it) },
            )
            OptionSetting(
                controller = controller,
                label = "Sleep duration",
                description = "Vendor option 1/2/3; the MCU's unit for these is unverified",
                key = SettingKeys.SLEEP_TIME,
                default = PowerOptions.SLEEP_TIME_DEFAULT,
                options = PowerOptions.SLEEP_TIME,
                enabled = controller.getBoolean(SettingKeys.SLEEP_SWITCH, false),
            )
        }

        SettingsSection(title = "Screen timeouts") {
            OptionSetting(
                controller = controller,
                label = "Screensaver after",
                description = "Idle time before the vendor screensaver starts",
                key = SettingKeys.AUTO_SCREENSAVER_TIME,
                default = PowerOptions.SCREEN_TIMEOUT_NEVER,
                options = PowerOptions.SCREEN_TIMEOUT,
            )
            OptionSetting(
                controller = controller,
                label = "Screen off after",
                description = if (closeScreenActive) {
                    "Idle time before the screen is switched off"
                } else {
                    "The gateway only honours this on customer type " +
                        "${PowerOptions.CLOSE_SCREEN_CUSTOMER_TYPE}; this unit is not"
                },
                key = SettingKeys.AUTO_CLOSE_SCREEN_TIME,
                default = PowerOptions.SCREEN_TIMEOUT_NEVER,
                options = PowerOptions.SCREEN_TIMEOUT,
            )
        }

        Text(
            text = "Domains are transcribed from the vendor settings app. A stored value outside " +
                "them is shown raw and left alone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A slider over a SysVar with a known integer range. A stored value outside it is rendered
 * read-only, raw: a plain slider would coerce it and commit the coerced value on the first
 * touch, destroying the original. A missing key falls back to [default] and stays adjustable.
 */
@Composable
private fun RangeSetting(
    controller: CarSettingsController,
    label: String,
    description: String,
    key: String,
    default: Int,
    range: IntRange,
    format: (Int) -> String,
) {
    val raw = controller.getString(key)
    val value = if (raw.isBlank()) default else PowerOptions.rawOrNull(raw, range)

    if (value == null) {
        InfoRow(label = label, value = "$raw — read-only, outside $range")
        return
    }

    SliderSetting(
        label = label,
        description = description,
        value = value,
        range = range,
        onChange = { controller.setInt(key, it) },
        format = format,
    )
}

/** The enum counterpart of [RangeSetting]: a picker over `raw -> label` options, same guard. */
@Composable
private fun OptionSetting(
    controller: CarSettingsController,
    label: String,
    description: String,
    key: String,
    default: Int,
    options: List<Pair<Int, String>>,
    enabled: Boolean = true,
) {
    val raw = controller.getString(key)
    val value = if (raw.isBlank()) default else PowerOptions.rawOrNull(raw, options)

    if (value == null) {
        InfoRow(label = label, value = "$raw — read-only, not a vendor option")
        return
    }

    PickerSetting(
        label = label,
        description = description,
        current = value,
        options = options,
        onSelect = { controller.setInt(key, it) },
        enabled = enabled,
    )
}
