package com.reveng.carlauncher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.SettingKeys

/**
 * v1.6 — Climate. Mirrors the vendor A/C configuration, reskinned, plus a live HVAC readout
 * from [CarEvents.climate] (best-effort decode of the `carairstruct` broadcast). Config values
 * are SysVar-backed (CAR_API §2.3). The readout degrades to a placeholder when no valid frame
 * has arrived, so a guessed byte layout never fabricates numbers.
 */
@Composable
fun ClimateSettingsScreen(
    controller: CarSettingsController,
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    snap
    val climate by carEvents.climate.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Climate", onBack = onBack) {
        SettingsSection(title = "Live A/C") {
            val cs = climate
            if (cs == null || !cs.valid) {
                Text(
                    text = "No live climate data yet. The readout populates when the A/C bus " +
                        "reports state.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ReadoutTile("Left", cs.leftTempLabel())
                    ReadoutTile("Right", cs.rightTempLabel())
                    ReadoutTile("Fan", cs.fanLevel.toString())
                }
                Text(
                    text = buildString {
                        append(if (cs.acOn) "A/C on" else "A/C off")
                        if (cs.autoOn) append(" · Auto")
                        if (cs.dualOn) append(" · Dual")
                        if (cs.rearAirOn) append(" · Rear")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsSection(title = "A/C hardware") {
            PickerSetting(
                label = "A/C panel protocol",
                current = controller.getInt(SettingKeys.AIR_PANEL_TYPE, 0),
                options = listOf(
                    0 to "Type A",
                    1 to "Type B",
                    2 to "Type C",
                    3 to "Type D",
                ),
                onSelect = { controller.setInt(SettingKeys.AIR_PANEL_TYPE, it) },
                description = "Serial protocol for the A/C control board",
            )
            // v0.4.7 — refuse-listed link speed (ProtectedSettingKeys): a wrong write silences
            // climate control. Read-only raw value — the old picker wrote an invented enum
            // mapping and fabricated "9600" when the key was absent.
            InfoRow(
                "A/C board baud rate",
                controller.getString(SettingKeys.AIR_CONDITIONING_BAUD, "—").ifBlank { "—" },
            )
        }

        SettingsSection(title = "Comfort") {
            ToggleSetting(
                label = "Heated seats",
                checked = controller.getBoolean(SettingKeys.SEAT_HEAT, false),
                onChange = { controller.setBoolean(SettingKeys.SEAT_HEAT, it) },
            )
            ToggleSetting(
                label = "Ventilated seats",
                checked = controller.getBoolean(SettingKeys.SEAT_COOL, false),
                onChange = { controller.setBoolean(SettingKeys.SEAT_COOL, it) },
            )
            ToggleSetting(
                label = "Heated steering wheel",
                checked = controller.getBoolean(SettingKeys.WHEEL_HEAT, false),
                onChange = { controller.setBoolean(SettingKeys.WHEEL_HEAT, it) },
            )
        }

        SettingsSection(title = "Display") {
            PickerSetting(
                label = "Temperature unit",
                current = controller.getInt(SettingKeys.TEMP_UNIT, 0),
                options = listOf(0 to "°C", 1 to "°F"),
                onSelect = { controller.setInt(SettingKeys.TEMP_UNIT, it) },
            )
            ToggleSetting(
                label = "Show temperature",
                checked = controller.getBoolean(SettingKeys.SHOW_TEMP, true),
                onChange = { controller.setBoolean(SettingKeys.SHOW_TEMP, it) },
            )
            ToggleSetting(
                label = "Rear air present",
                description = "This vehicle has a rear A/C zone",
                checked = controller.getBoolean(SettingKeys.REAR_AIR, false),
                onChange = { controller.setBoolean(SettingKeys.REAR_AIR, it) },
            )
            ToggleSetting(
                label = "Show A/C bar",
                description = "Pop up the climate bar when A/C changes",
                checked = controller.getBoolean(SettingKeys.BAR_AIR_SHOW, true),
                onChange = { controller.setBoolean(SettingKeys.BAR_AIR_SHOW, it) },
            )
        }
    }
}

@Composable
private fun ReadoutTile(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
