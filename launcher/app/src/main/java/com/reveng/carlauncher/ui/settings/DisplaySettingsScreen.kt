package com.reveng.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.data.BrightnessController
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.SettingKeys

/**
 * v2.1 — Display & Illumination.
 *
 * The **Screen backlight** slider now actually changes the display: it drives the Android
 * framework brightness (`Settings.System.SCREEN_BRIGHTNESS`) through [BrightnessController],
 * which works without root once WRITE_SETTINGS is granted (a one-tap prompt is shown otherwise),
 * and also pushes the level to the MCU over the vendor AIDL. The vendor illumination SysVars
 * (day/night targets, panel & ambient lighting) are kept under their own section — those still
 * need root/a privileged install to take effect, which the hub already warns about.
 */
@Composable
fun DisplaySettingsScreen(
    controller: CarSettingsController,
    carService: CarService,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    snap

    // Live WRITE_SETTINGS status, re-checked on resume (e.g. after the grant screen).
    var canWrite by remember { mutableStateOf(BrightnessController.canWrite(context)) }
    // null = the backlight level is unreadable; the slider has no position to show, so it stays
    // disabled rather than inventing one.
    var brightness by remember { mutableStateOf(BrightnessController.currentPercent(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                canWrite = BrightnessController.canWrite(context)
                brightness = BrightnessController.currentPercent(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    SettingsScaffold(title = "Display & Illumination", onBack = onBack) {
        SettingsSection(title = "Screen backlight") {
            SliderSetting(
                label = "Brightness",
                description = when {
                    !canWrite -> "Grant permission below to control the backlight live"
                    brightness == null -> "The current backlight level cannot be read"
                    else -> "Changes the display immediately"
                },
                value = brightness ?: 0,
                range = 0..100,
                onChange = {
                    brightness = it
                    BrightnessController.setPercent(context, it, carService)
                },
                enabled = canWrite && brightness != null,
                format = { "$it%" },
            )
            if (!canWrite) {
                ActionRow(
                    label = "Enable live brightness control",
                    description = "Opens Android's \"Modify system settings\" for Car Launcher",
                    onClick = { BrightnessController.requestPermission(context) },
                )
            }
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

        SettingsSection(title = "Vendor illumination") {
            Text(
                text = "These write the vendor's illumination store and take effect only with " +
                    "root / a privileged install.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SliderSetting(
                label = "Vendor backlight level",
                value = controller.getInt(SettingKeys.LIGHT_LEVEL_SET, 60),
                range = 0..100,
                onChange = { controller.setInt(SettingKeys.LIGHT_LEVEL_SET, it) },
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
            SliderSetting(
                label = "Contrast",
                value = controller.getInt(SettingKeys.CONTRAST, 50),
                range = 0..100,
                onChange = { controller.setInt(SettingKeys.CONTRAST, it) },
                format = { "$it%" },
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
    }
}
