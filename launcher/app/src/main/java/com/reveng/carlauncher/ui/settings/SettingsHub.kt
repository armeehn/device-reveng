package com.reveng.carlauncher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Security // v2.9
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.data.CarSettingsController

/**
 * v1.1 — the Settings hub: a categorized menu mirroring the vendor GT6 settings top level,
 * reskinned. Each card pushes a detail screen via [onOpen]. "Launcher" holds our own app
 * preferences (grid density, widgets, default-home helper — migrated from the old flat
 * SettingsScreen); the rest are the reskinned vendor categories backed by SysVar/AIDL.
 */
@Composable
fun SettingsHub(
    controller: CarSettingsController,
    onOpen: (SettingsRoute) -> Unit,
    onBack: () -> Unit,
) {
    val root by controller.rootAvailable.collectAsStateWithLifecycle()
    val subtitle = when (root) {
        false -> "Read-only: root not detected, changes to car settings won't persist"
        else -> "Car & launcher configuration"
    }

    SettingsScaffold(title = "Settings", onBack = onBack, subtitle = subtitle) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCategoryCard(
                icon = Icons.Filled.Widgets,
                title = "Launcher",
                subtitle = "Home widgets, grid density, default home, day/night",
                onClick = { onOpen(SettingsRoute.LauncherPrefs) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.BrightnessMedium,
                title = "Display & Illumination",
                subtitle = "Brightness, backlight, panel & ambient lighting",
                onClick = { onOpen(SettingsRoute.Display) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.Videocam,
                title = "Reverse camera",
                subtitle = "Video input, mirroring, guide lines, auto-exit",
                onClick = { onOpen(SettingsRoute.ReverseCamera) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.Sensors,
                title = "Parking radar",
                subtitle = "Sensor type, warning tone, live distances",
                onClick = { onOpen(SettingsRoute.Radar) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.GraphicEq,
                title = "Audio & EQ",
                subtitle = "Equalizer, balance/fader, loudness, subwoofer",
                onClick = { onOpen(SettingsRoute.Audio) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.Thermostat,
                title = "Climate",
                subtitle = "A/C panel, rear air, live HVAC readout",
                onClick = { onOpen(SettingsRoute.Climate) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.Radio,
                title = "Radio",
                subtitle = "Region/band, RDS, presets",
                onClick = { onOpen(SettingsRoute.Radio) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.SettingsRemote,
                title = "Steering wheel",
                subtitle = "Learn & map wheel keys, live key monitor",
                onClick = { onOpen(SettingsRoute.SteeringWheel) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.PowerSettingsNew,
                title = "Power & sleep",
                subtitle = "ACC delays, sleep, power-off timing",
                onClick = { onOpen(SettingsRoute.Power) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.Security,
                title = "Root tier",
                subtitle = "Protected broadcasts, vendor bars, sole-HOME mode",
                onClick = { onOpen(SettingsRoute.RootTier) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.Info,
                title = "System & about",
                subtitle = "Versions, car profile, screen, reset & reboot",
                onClick = { onOpen(SettingsRoute.System) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.DataObject,
                title = "CAN frame capture",
                subtitle = "Raw CAN bulk frame — the route to real speed (diagnostic)",
                onClick = { onOpen(SettingsRoute.CanCapture) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.Radio,
                title = "Radio info capture",
                subtitle = "Raw radio broadcast -- the route to a station name (diagnostic)",
                onClick = { onOpen(SettingsRoute.RadioInfoCapture) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.DirectionsCar,
                title = "Vehicle data capture",
                subtitle = "Sniff undecoded CAN events: trip, fuel, TPMS, seat (diagnostic)",
                onClick = { onOpen(SettingsRoute.VehicleDataCapture) },
            )
            SettingsCategoryCard(
                icon = Icons.Filled.Tune,
                title = "All settings (advanced)",
                subtitle = "Raw SysVar browser — every vendor key",
                onClick = { onOpen(SettingsRoute.Advanced) },
            )
        }
    }
}
