package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.ui.theme.carCard
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.ripostelabs.carlauncher.ui.theme.carShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.data.BrightnessController
import com.ripostelabs.carlauncher.data.DayNightMode
import com.ripostelabs.carlauncher.data.LauncherSettings
import com.ripostelabs.carlauncher.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.6 — Quick Controls: a compact control panel for the things a driver reaches for most
 * without leaving the launcher — Volume (+ mute), screen Brightness, a manual Day/Night
 * theme override, and Wi‑Fi / Bluetooth shortcuts.
 *
 * Everything that touches the vendor AIDL ([CarService]) or root ([RootShell]) is wrapped in
 * try/catch and degrades gracefully (the affected control simply disables) so an unavailable
 * service never crashes the launcher. Volume uses the vendor EventService; brightness goes
 * through BrightnessController (framework WRITE_SETTINGS, band-mapped into the panel's usable
 * likely denied to a side-loaded app.
 */

/**
 * The status-bar affordance: a "tune" icon that opens the Quick Controls panel as a dialog.
 * Kept self-contained here so [StatusBar] only needs a one-line call (v0.6, low merge risk).
 */
@Composable
fun QuickControlsButton(
    carService: CarService,
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val press = withTapFeedback { open = true } // v2.5
    Icon(
        imageVector = Icons.Filled.Tune,
        contentDescription = "Quick controls",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(28.dp)
            .clickable(onClick = press),
    )
    if (open) {
        QuickControlsDialog(
            carService = carService,
            settingsStore = settingsStore,
            onDismiss = { open = false },
        )
    }
}

/**
 * v3.1 — the same dialog with its open state hoisted to the caller: lets the status chips
 * (or any future affordance) open Quick Controls without duplicating the panel.
 * [QuickControlsButton] above keeps its own private state and is unchanged.
 */
@Composable
fun QuickControlsDialogHost(
    open: Boolean,
    onDismiss: () -> Unit,
    carService: CarService,
    settingsStore: SettingsStore,
) {
    if (open) {
        QuickControlsDialog(
            carService = carService,
            settingsStore = settingsStore,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun QuickControlsDialog(
    carService: CarService,
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = carShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .padding(8.dp)
                .carCard(),
        ) {
            QuickControlsPanel(
                carService = carService,
                settingsStore = settingsStore,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

/** The panel body — usable standalone (e.g. a future pull-down) or inside the dialog. */
@Composable
fun QuickControlsPanel(
    carService: CarService,
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by settingsStore.settings.collectAsStateSafe(initial = LauncherSettings())

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            text = "Quick controls",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )

        VolumeControl(carService = carService)
        BrightnessControl(carService = carService)
        DayNightControl(
            mode = settings.dayNightMode,
            onSelect = settingsStore::setDayNightMode,
        )

        // ---- Wi-Fi / Bluetooth system-panel shortcuts (Intents) -------------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShortcutChip(
                icon = Icons.Filled.Wifi,
                label = "Wi-Fi",
                modifier = Modifier.weight(1f),
                onClick = { launchSettings(context, Settings.ACTION_WIFI_SETTINGS) },
            )
            ShortcutChip(
                icon = Icons.Filled.Bluetooth,
                label = "Bluetooth",
                modifier = Modifier.weight(1f),
                onClick = { launchSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) },
            )
        }
    }
}

// ---- Volume ----------------------------------------------------------------

@Composable
private fun VolumeControl(carService: CarService) {
    // Read once from the vendor service; unavailable -> control disabled.
    var available by remember { mutableStateOf(true) }
    var volume by remember { mutableStateOf(0f) }
    var muted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val v = withContext(Dispatchers.IO) {
            runCatching { carService.getMainVolume() }.getOrNull()
        }
        val m = withContext(Dispatchers.IO) {
            runCatching { carService.isMuteOn() }.getOrNull()
        }
        if (v == null && m == null) {
            available = false
        } else {
            volume = (v ?: 0).toFloat()
            muted = m ?: false
        }
    }

    // Conflated single-consumer pipe, the same shape BrightnessControl uses below. The slider
    // fires on every pointer move, and CarService.setVolume is *two* blocking AIDL round-trips
    // (IsMuteOn then sendVolState) — inline that was tens of main-thread binder calls a second
    // during a drag. Conflated, not queued: after the finger lifts only the final level matters.
    val volumeWrites = remember { Channel<Int>(Channel.CONFLATED) }
    LaunchedEffect(Unit) {
        for (level in volumeWrites) {
            withContext(Dispatchers.IO) { runCatching { carService.setVolume(level) } }
        }
    }

    // setMute is a non-oneway sendMuteState round-trip into the vendor gateway. One call per
    // tap rather than a storm, but it was the last main-thread vendor call here — so it goes
    // through the same conflated pipe. The value is captured before the hop: reading `muted`
    // inside the coroutine would send whatever the state held by then, not what the tap chose.
    val muteWrites = remember { Channel<Boolean>(Channel.CONFLATED) }
    LaunchedEffect(Unit) {
        for (state in muteWrites) {
            withContext(Dispatchers.IO) { runCatching { carService.setMute(state) } }
        }
    }

    val toggleMute = withTapFeedback { // v2.5
        val next = !muted
        muted = next
        muteWrites.trySend(next)
    }

    ControlRow(
        icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
        title = if (available) "Volume" else "Volume (unavailable)",
        onIconClick = if (available) toggleMute else null,
    ) {
        Slider(
            value = volume,
            onValueChange = { v ->
                volume = v
                if (available) volumeWrites.trySend(v.toInt())
            },
            valueRange = 0f..CarService.MAX_VOLUME.toFloat(),
            enabled = available && !muted,
            colors = accentSliderColors(),
        )
    }
}

// ---- Brightness ------------------------------------------------------------

@Composable
private fun BrightnessControl(carService: CarService) {
    val context = LocalContext.current
    // null = not resolved yet. canWrite is an AppOps binder call and currentPercent a
    // Settings.System query, so neither runs in the composable body; until the effect below
    // lands the row reads plainly as "Brightness" rather than claiming a missing permission.
    var canWrite by remember { mutableStateOf<Boolean?>(null) }
    // null = the current level is unreadable. The slider then has no honest position, so it is
    // disabled instead of parked at a made-up mid-travel value.
    var brightness by remember { mutableStateOf<Float?>(null) }

    // Refresh permission + current level when the shade (re)opens.
    LaunchedEffect(Unit) {
        val granted = withContext(Dispatchers.IO) { BrightnessController.canWrite(context) }
        val percent = withContext(Dispatchers.IO) { BrightnessController.currentPercent(context) }
        canWrite = granted
        brightness = percent?.toFloat()
    }

    // Conflated single-consumer pipe: a fast drag emits many values, keep only the latest and
    // apply one at a time. Brightness goes through BrightnessController, which maps the 0-100 %
    // slider into the panel's usable backlight band (raw ~6-20; it hardware-saturates at ~20/255)
    // and nudges the MCU backlight. The old raw `settings put screen_brightness 0-255` path did
    // nothing across most of the slider because the usable band is only the first ~8 % of 0-255.
    val brightnessWrites = remember { Channel<Int>(Channel.CONFLATED) }
    LaunchedEffect(Unit) {
        for (percent in brightnessWrites) {
            withContext(Dispatchers.IO) {
                runCatching { BrightnessController.setPercent(context, percent, carService) }
            }
        }
    }

    val level = brightness
    val adjustable = canWrite == true && level != null

    ControlRow(
        icon = Icons.Filled.BrightnessMedium,
        title = when {
            canWrite == false -> "Brightness (needs permission)"
            canWrite == true && level == null -> "Brightness (unavailable)"
            else -> "Brightness"
        },
    ) {
        Slider(
            value = level ?: 0f,
            onValueChange = { b ->
                brightness = b
                brightnessWrites.trySend(b.toInt())
            },
            valueRange = 0f..100f,
            enabled = adjustable,
            colors = accentSliderColors(),
        )
    }
}

// ---- Day / Night override --------------------------------------------------

@Composable
private fun DayNightControl(
    mode: DayNightMode,
    onSelect: (DayNightMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Day / night",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentChip(
                label = "Auto",
                selected = mode == DayNightMode.AUTO,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(DayNightMode.AUTO) },
            )
            SegmentChip(
                icon = Icons.Filled.LightMode,
                label = "Day",
                selected = mode == DayNightMode.FORCE_DAY,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(DayNightMode.FORCE_DAY) },
            )
            SegmentChip(
                icon = Icons.Filled.DarkMode,
                label = "Night",
                selected = mode == DayNightMode.FORCE_NIGHT,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(DayNightMode.FORCE_NIGHT) },
            )
        }
    }
}

// ---- Small shared pieces ---------------------------------------------------

@Composable
private fun ControlRow(
    icon: ImageVector,
    title: String,
    onIconClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .then(if (onIconClick != null) Modifier.clickable(onClick = onIconClick) else Modifier),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        content()
    }
}

@Composable
private fun ShortcutChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val press = withTapFeedback(onClick) // v2.5
    Row(
        modifier = modifier
            .clip(carShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = press)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SegmentChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val press = withTapFeedback(onClick) // v2.5
    Row(
        modifier = modifier
            .clip(carShape(12.dp))
            .background(bg)
            .clickable(onClick = press)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = label, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun accentSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
)

private fun launchSettings(context: Context, action: String) {
    runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
