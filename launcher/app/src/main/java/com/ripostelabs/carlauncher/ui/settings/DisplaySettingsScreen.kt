package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.BrightnessController
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

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

    // Live WRITE_SETTINGS status + backlight level, re-read on resume (e.g. after the grant
    // screen). canWrite is an AppOps binder call and currentPercent a provider query — neither
    // belongs in the composition body or an ON_RESUME callback on main (QuickControls'
    // BrightnessControl is the in-repo pattern). null = not resolved yet: the slider stays
    // disabled rather than claiming a missing permission or a made-up level.
    var canWrite by remember { mutableStateOf<Boolean?>(null) }
    var brightness by remember { mutableStateOf<Int?>(null) }
    var probe by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                probe++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(probe) {
        val granted = withContext(Dispatchers.IO) { BrightnessController.canWrite(context) }
        val percent = withContext(Dispatchers.IO) { BrightnessController.currentPercent(context) }
        canWrite = granted
        brightness = percent
    }

    // The shade's brightness slider already applies levels through a conflated pipe on
    // Dispatchers.IO; this is the second entry point to the same control, so it does the same.
    // BrightnessController.setPercent is a blocking sendBacklight() AIDL plus two
    // ContentResolver writes, none of which belongs on the main thread.
    val brightnessWrites = remember { Channel<Int>(Channel.CONFLATED) }
    LaunchedEffect(Unit) {
        for (percent in brightnessWrites) {
            withContext(Dispatchers.IO) {
                runCatching { BrightnessController.setPercent(context, percent, carService) }
            }
        }
    }

    SettingsScaffold(title = "Display & Illumination", onBack = onBack) {
        SettingsSection(title = "Screen backlight") {
            SliderSetting(
                label = "Brightness",
                description = when {
                    canWrite == null -> "Reading the current level…"
                    canWrite == false -> "Grant permission below to control the backlight live"
                    brightness == null -> "The current backlight level cannot be read"
                    else -> "Changes the display immediately"
                },
                value = brightness ?: 0,
                range = 0..100,
                onChange = {
                    brightness = it
                    brightnessWrites.trySend(it)
                },
                enabled = canWrite == true && brightness != null,
                format = { "$it%" },
            )
            if (canWrite == false) {
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
