package com.reveng.carlauncher.ui.settings

import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.SettingKeys

/**
 * v1.2 — Display & Illumination. Mirrors the vendor "Display/Backlight" settings page,
 * reskinned. All values are backed by the vendor SysVar store through [CarSettingsController]
 * (CAR_API §2.3); writing them needs root / a privileged install, surfaced on the hub.
 *
 * ⚠ Value ranges/enums for several of these are inferred from the key naming (CAR_API notes
 * ranges as "[inferred]"). Sliders use a 0–100 model; verify against the device and adjust the
 * [SettingKeys] metadata if the MCU uses a different scale.
 */
@Composable
fun DisplaySettingsScreen(
    controller: CarSettingsController,
    onBack: () -> Unit,
) {
    // Recompose whenever the SysVar snapshot changes (a write or an external change).
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    // Reference snap so this composable is keyed to it (values read via controller getters).
    snap

    SettingsScaffold(title = "Display & Illumination", onBack = onBack) {
        SettingsSection(title = "Brightness") {
            SliderSetting(
                label = "Backlight level",
                description = "Current display brightness",
                value = controller.getInt(SettingKeys.LIGHT_LEVEL_SET, 60),
                range = 0..100,
                onChange = { controller.setInt(SettingKeys.LIGHT_LEVEL_SET, it) },
                format = { "$it%" },
            )
            SliderSetting(
                label = "Brightness",
                value = controller.getInt(SettingKeys.BRIGHTNESS, 60),
                range = 0..100,
                onChange = { controller.setInt(SettingKeys.BRIGHTNESS, it) },
                format = { "$it%" },
            )
            SliderSetting(
                label = "Contrast",
                value = controller.getInt(SettingKeys.CONTRAST, 50),
                range = 0..100,
                onChange = { controller.setInt(SettingKeys.CONTRAST, it) },
                format = { "$it%" },
            )
            SliderSetting(
                label = "Daytime brightness",
                description = "Target level when illumination is day",
                value = controller.getInt(SettingKeys.SET_DAY_LIGHT, 80),
                range = 0..100,
                onChange = { controller.setInt(SettingKeys.SET_DAY_LIGHT, it) },
                format = { "$it%" },
            )
            SliderSetting(
                label = "Night brightness",
                description = "Target level when illumination is night",
                value = controller.getInt(SettingKeys.SET_NIGHT_LIGHT, 40),
                range = 0..100,
                onChange = { controller.setInt(SettingKeys.SET_NIGHT_LIGHT, it) },
                format = { "$it%" },
            )
        }

        SettingsSection(title = "Day / night source") {
            PickerSetting(
                label = "Day/night mode source",
                current = controller.getInt(SettingKeys.DAY_NIGHT_MODE, 0),
                options = listOf(
                    0 to "Auto (illumination)",
                    1 to "Always day",
                    2 to "Always night",
                ),
                onSelect = { controller.setInt(SettingKeys.DAY_NIGHT_MODE, it) },
                description = "How the head unit decides day vs night",
            )
        }

        SettingsSection(title = "Panel & key lighting") {
            ToggleSetting(
                label = "Panel button illumination",
                checked = controller.getBoolean(SettingKeys.MCU_PANEL_LIGHT, true),
                onChange = { controller.setBoolean(SettingKeys.MCU_PANEL_LIGHT, it) },
            )
            ToggleSetting(
                label = "Soft (CAN) light control",
                description = "Follow the car's dimming bus instead of the ILL wire",
                checked = controller.getBoolean(SettingKeys.MCU_SOFT_LIGHT_CONTROL, false),
                onChange = { controller.setBoolean(SettingKeys.MCU_SOFT_LIGHT_CONTROL, it) },
            )
            ToggleSetting(
                label = "Ambient light",
                checked = controller.getBoolean(SettingKeys.CAR_AMBIENT_LIGHT, false),
                onChange = { controller.setBoolean(SettingKeys.CAR_AMBIENT_LIGHT, it) },
            )
            SliderSetting(
                label = "Multicolour key light",
                description = "Backlight colour index for the hard keys",
                value = controller.getInt(SettingKeys.MULTICOLOR_KEY_LIGHT, 0),
                range = 0..15,
                onChange = { controller.setInt(SettingKeys.MULTICOLOR_KEY_LIGHT, it) },
            )
        }

        Text(
            text = "Note: illumination scales are inferred from the vendor firmware; if the " +
                "slider feels wrong on-device it maps to a different range.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
