package com.reveng.carlauncher.ui

import com.reveng.carlauncher.ui.theme.carCard
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
import com.reveng.carlauncher.ui.theme.carShape
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
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.carlib.RootShell
import com.reveng.carlauncher.data.DayNightMode
import com.reveng.carlauncher.data.LauncherSettings
import com.reveng.carlauncher.data.SettingsStore
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
 * service never crashes the launcher. Volume uses the vendor EventService; brightness is
 * written with `settings put system screen_brightness` over root, because WRITE_SETTINGS is
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
    Icon(
        imageVector = Icons.Filled.Tune,
        contentDescription = "Quick controls",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(28.dp)
            .clickable { open = true },
    )
    if (open) {
        QuickControlsDialog(
            carService = carService,
            settingsStore = settingsStore,
            onDismiss = { open = false },
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
        BrightnessControl()
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

    ControlRow(
        icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
        title = if (available) "Volume" else "Volume (unavailable)",
        onIconClick = if (available) {
            {
                muted = !muted
                runCatching { carService.setMute(muted) }
            }
        } else null,
    ) {
        Slider(
            value = volume,
            onValueChange = { v ->
                volume = v
                if (available) runCatching { carService.setVolume(v.toInt()) }
            },
            valueRange = 0f..CarService.MAX_VOLUME.toFloat(),
            enabled = available && !muted,
            colors = accentSliderColors(),
        )
    }
}

// ---- Brightness ------------------------------------------------------------

@Composable
private fun BrightnessControl() {
    var available by remember { mutableStateOf(true) }
    var brightness by remember { mutableStateOf(128f) }

    // Conflated single-consumer pipe: a fast drag emits many values, but only the latest
    // unconsumed one is kept and writes run strictly one at a time. This replaces the old
    // "launch a root-shell write per onValueChange tick" which flooded the unit with concurrent
    // `su` invocations that could complete out of order and leave a stale persisted brightness.
    val brightnessWrites = remember { Channel<Int>(Channel.CONFLATED) }

    LaunchedEffect(Unit) {
        val cur = withContext(Dispatchers.IO) {
            runCatching {
                val res = RootShell.exec("settings get system screen_brightness")
                if (res.ok) res.stdout.trim().toIntOrNull() else null
            }.getOrNull()
        }
        if (cur == null) available = false else brightness = cur.toFloat()
    }

    LaunchedEffect(Unit) {
        for (value in brightnessWrites) {
            withContext(Dispatchers.IO) {
                runCatching { RootShell.exec("settings put system screen_brightness $value") }
            }
        }
    }

    ControlRow(
        icon = Icons.Filled.BrightnessMedium,
        title = if (available) "Brightness" else "Brightness (needs root)",
    ) {
        Slider(
            value = brightness,
            onValueChange = { b ->
                brightness = b
                if (available) brightnessWrites.trySend(b.toInt())
            },
            valueRange = 0f..255f,
            enabled = available,
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
    Row(
        modifier = modifier
            .clip(carShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
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
    Row(
        modifier = modifier
            .clip(carShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
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
