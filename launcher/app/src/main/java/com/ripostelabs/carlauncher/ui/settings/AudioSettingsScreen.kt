package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.5 — Audio & EQ. Mirrors the vendor sound settings, reskinned. The live audio state
 * (EQ preset, balance/fader, loudness, subwoofer) comes from the bound vendor control service
 * over AIDL (CAR_API §3.2, ordinals confirmed). AIDL getters aren't reactive, so we read once
 * when the bind is live and update our local echo as the user moves controls; speed-linked
 * volume + speed unit are SysVar-backed.
 *
 * ⚠ EQ preset indices and balance/fader ranges are vendor-defined and GUESSED here — verify
 * on-device. Control side-effects "work best as a system app" (CAR_API §3.1).
 */
@Composable
fun AudioSettingsScreen(
    controller: CarSettingsController,
    carService: CarService,
    onBack: () -> Unit,
) {
    val connected by carService.connected.collectAsStateWithLifecycle()
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    snap

    // Local echo of live AIDL audio state, seeded once the service is connected.
    var eqMode by remember { mutableIntStateOf(0) }
    var balance by remember { mutableIntStateOf(0) }
    var fader by remember { mutableIntStateOf(0) }
    var subVol by remember { mutableIntStateOf(0) }
    var loudness by remember { mutableStateOf(false) }
    var seeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Seed off the main thread. These four getters are blocking AIDL round-trips and ran in the
    // composition body, so opening Audio & EQ made four synchronous gateway calls before the
    // first frame — and stalled composition outright if the gateway was wedged.
    LaunchedEffect(connected) {
        if (!connected || seeded) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            eqMode = carService.getEqMode() ?: 0
            carService.getBalanceFader()?.let {
                if (it.size >= 2) { balance = it[0]; fader = it[1] }
            }
            subVol = carService.getSubVolume() ?: 0
            loudness = carService.getLoudness() ?: false
        }
        seeded = true
    }

    /** Apply one vendor write off the main thread; the local echo has already updated. */
    fun control(action: () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { runCatching(action) } }
    }

    SettingsScaffold(
        title = "Audio & EQ",
        onBack = onBack,
        subtitle = if (connected) null else "Waiting for the vendor audio service…",
    ) {
        SettingsSection(title = "Equalizer") {
            PickerSetting(
                label = "EQ preset",
                current = eqMode,
                options = listOf(
                    0 to "Flat",
                    1 to "Pop",
                    2 to "Rock",
                    3 to "Jazz",
                    4 to "Classic",
                    5 to "Vocal",
                    6 to "Custom",
                ),
                onSelect = { eqMode = it; control { carService.setEqMode(it) } },
                enabled = connected,
            )
        }

        SettingsSection(title = "Balance & fader") {
            SliderSetting(
                label = "Balance",
                description = "Left ↔ right",
                value = balance,
                range = -8..8,
                onChange = { balance = it; control { carService.setBalanceFader(it, fader) } },
                enabled = connected,
                format = { balanceLabel(it) },
            )
            SliderSetting(
                label = "Fader",
                description = "Front ↔ rear",
                value = fader,
                range = -8..8,
                onChange = { fader = it; control { carService.setBalanceFader(balance, it) } },
                enabled = connected,
                format = { faderLabel(it) },
            )
        }

        SettingsSection(title = "Enhancements") {
            VolumeSlider(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = "Subwoofer level",
                value = subVol,
                range = 0..20,
                onChange = { subVol = it; control { carService.setSubVolume(it) } },
                enabled = connected,
            )
            InfoRow(label = "Loudness (live)", value = if (loudness) "On" else "Off")
            ToggleSetting(
                label = "DSP loudness",
                checked = controller.getBoolean(SettingKeys.DSP_LOUDNESS, false),
                onChange = { controller.setBoolean(SettingKeys.DSP_LOUDNESS, it) },
            )
            ToggleSetting(
                label = "Touch beep",
                checked = controller.getBoolean(SettingKeys.TOUCH_BEEP, true),
                onChange = { controller.setBoolean(SettingKeys.TOUCH_BEEP, it) },
            )
            ToggleSetting(
                label = "Mute audio when reversing",
                checked = controller.getBoolean(SettingKeys.REVERSING_ATTENUATION, false),
                onChange = { controller.setBoolean(SettingKeys.REVERSING_ATTENUATION, it) },
            )
            ActionRow(
                label = "Test beep",
                description = "Play a short tone through the audio path",
                onClick = { control { carService.beep() } },
                enabled = connected,
            )
        }

        SettingsSection(title = "OEM source volume") {
            Text(
                text = "Per-source volume trim relative to the main volume. Vendor gains — persist " +
                    "with root / a privileged install.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val sources = listOf(
                Triple(Icons.Filled.MusicNote, "Music", SettingKeys.VOL_MUSIC),
                Triple(Icons.Filled.Bluetooth, "Bluetooth music", SettingKeys.VOL_BT_MUSIC),
                Triple(Icons.Filled.Call, "Bluetooth call", SettingKeys.VOL_BT_CALL),
                Triple(Icons.Filled.Radio, "Radio", SettingKeys.VOL_RADIO),
                Triple(Icons.Filled.Usb, "USB", SettingKeys.VOL_USB),
                Triple(Icons.Filled.Cable, "AUX", SettingKeys.VOL_AUX),
                Triple(Icons.Filled.Movie, "DVD / video", SettingKeys.VOL_DVD),
                Triple(Icons.Filled.Tv, "TV", SettingKeys.VOL_TV),
                Triple(Icons.Filled.Navigation, "Navigation mix", SettingKeys.VOL_NAV_MIX),
                Triple(Icons.AutoMirrored.Filled.VolumeUp, "Other", SettingKeys.VOL_OTHER),
            )
            sources.forEach { (icon, label, key) ->
                VolumeSlider(
                    icon = icon,
                    label = label,
                    value = controller.getInt(key, 20),
                    range = 0..40,
                    onChange = { controller.setInt(key, it) },
                )
            }
            ToggleSetting(
                label = "Match OEM head-unit volume",
                description = "Track the factory radio's volume level",
                checked = controller.getBoolean(SettingKeys.ADJUST_OEM_VOLUME, false),
                onChange = { controller.setBoolean(SettingKeys.ADJUST_OEM_VOLUME, it) },
            )
        }

        SettingsSection(title = "Speed & volume") {
            PickerSetting(
                label = "Speed unit",
                current = controller.getInt(SettingKeys.CAR_SPEED_UNIT, 0),
                options = listOf(0 to "km/h", 1 to "mph"),
                onSelect = { controller.setInt(SettingKeys.CAR_SPEED_UNIT, it) },
            )
            ToggleSetting(
                label = "Show speed overlay",
                checked = controller.getBoolean(SettingKeys.SHOW_CAR_SPEED, false),
                onChange = { controller.setBoolean(SettingKeys.SHOW_CAR_SPEED, it) },
            )
        }

        Text(
            text = "EQ presets and balance/fader ranges are vendor-defined; if a preset name " +
                "doesn't match what you hear, the index maps to a different curve on this unit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun balanceLabel(v: Int): String = when {
    v == 0 -> "Center"
    v < 0 -> "L${-v}"
    else -> "R$v"
}

private fun faderLabel(v: Int): String = when {
    v == 0 -> "Center"
    v < 0 -> "F${-v}"
    else -> "R$v"
}
