package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.ripostelabs.carlauncher.ui.theme.carShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle // v2.8
import com.ripostelabs.carlauncher.HomeRole
import com.ripostelabs.carlauncher.carlib.CarEvents // v2.5
import com.ripostelabs.carlauncher.carlib.GpsSpeedSource // v2.5
import com.ripostelabs.carlauncher.carlib.RootShell // v2.5 shade
import com.ripostelabs.carlauncher.data.CarSettingsController // v2.8
import com.ripostelabs.carlauncher.data.DayNightMode
import com.ripostelabs.carlauncher.data.DriverSideMode // v2.8
import com.ripostelabs.carlauncher.data.LauncherSettings
import com.ripostelabs.carlauncher.data.SettingKeys // v2.8
import com.ripostelabs.carlauncher.data.SettingsStore
import com.ripostelabs.carlauncher.ui.collectAsStateSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.1 — the launcher's own preferences, migrated from the original flat SettingsScreen into
 * the new settings hub and rebuilt on the reskinned component kit. These are app-local
 * DataStore prefs (NOT car SysVars): default-home helper, app-grid density, which Home widgets
 * show, and the day/night theme policy.
 */
@Composable
fun LauncherPrefsScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit,
    carEvents: CarEvents? = null, // v2.5 motion gating (null keeps previews working)
    controller: CarSettingsController? = null, // v2.8 raw Sys_CarType readout
) {
    val context = LocalContext.current
    val settings by settingsStore.settings.collectAsStateSafe(initial = LauncherSettings())

    // v2.5: gate the "replace system bars" toggle on root — the suppression is a root shell op.
    // Probed once off the main thread; defaults to enabled so the toggle isn't wrongly greyed
    // before the probe resolves on a rooted unit.
    var rootAvailable by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        rootAvailable = withContext(Dispatchers.IO) { RootShell.isRootAvailable() }
    }

    // v0.4.7 — turning motion gating OFF unlocks every gated surface, so it takes the
    // parked-gated destructive confirm rather than one tap at speed. ON stays one tap.
    var confirmGateOff by remember { mutableStateOf(false) }

    // HomeRole.isDefaultHome is a PackageManager.resolveActivity binder round-trip, so it is
    // resolved off the main thread and seeded false rather than run in the composable body on
    // every entry to this screen. `homeProbe` re-runs it on resume (return from the picker).
    var isDefaultHome by remember { mutableStateOf(false) }
    var homeProbe by remember { mutableStateOf(0) }
    LaunchedEffect(homeProbe) {
        isDefaultHome = withContext(Dispatchers.IO) { HomeRole.isDefaultHome(context) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                homeProbe++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScaffold(title = "Launcher", onBack = onBack) {
        SettingsSection(title = "Default home") {
            if (isDefaultHome) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Car Launcher is your default home.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                ActionRow(
                    label = "Set as default home",
                    description = "Pick \"Car Launcher\" in the Android home chooser.",
                    onClick = { HomeRole.requestSetDefaultHome(context) },
                )
            }
        }

        SettingsSection(title = "App grid density") {
            Text(
                text = "Columns in the app drawer grid.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cols in LauncherSettings.MIN_GRID_COLUMNS..LauncherSettings.MAX_GRID_COLUMNS) {
                    ColumnChip(
                        count = cols,
                        selected = settings.gridColumns == cols,
                        onClick = { settingsStore.setGridColumns(cols) },
                    )
                }
            }
        }

        SettingsSection(title = "Home widgets") {
            ToggleSetting("Media card", settings.showMedia, settingsStore::setShowMedia)
            ToggleSetting("Radio card", settings.showRadio, settingsStore::setShowRadio)
            ToggleSetting("Climate readout", settings.showClimate, settingsStore::setShowClimate)
            ToggleSetting("Navigation", settings.showNav, settingsStore::setShowNav)
            ToggleSetting(
                label = "Video mini screen",
                description = if (rootAvailable)
                    "When a video app is playing, float it as a small window over the " +
                        "media card on Home. Parked only."
                else "Needs root — enabling freeform windows on this build requires it.",
                checked = settings.videoMiniScreen,
                onChange = settingsStore::setVideoMiniScreen,
                enabled = rootAvailable,
            )
        }

        // v0.4.2: text-to-speech. Off by default — a launcher that talks unasked is worse
        // than silent (LAUNCHER_DESIGN eyes-free posture; pairs with the car's own beep feedback).
        SettingsSection(title = "Read aloud") {
            ToggleSetting(
                "Read now-playing aloud",
                settings.readNowPlaying,
                settingsStore::setReadNowPlaying,
            )
            ToggleSetting(
                "Read notifications aloud",
                settings.readNotifications,
                settingsStore::setReadNotifications,
            )
        }

        SettingsSection(title = "Top bar & shade") {
            Text(
                text = "The launcher can show its own swipe-from-top Quick Controls shade " +
                    "(volume, brightness, day/night, Wi-Fi, Bluetooth) that matches your theme.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            ToggleSetting(
                label = "Swipe-down Quick Controls",
                description = "Swipe from the top edge of Home to open the launcher shade.",
                checked = settings.shadeEnabled,
                onChange = settingsStore::setShadeEnabled,
            )
            ToggleSetting(
                label = "Replace the system top bar",
                description = if (rootAvailable)
                    "Hide the vendor status bar and Android pull-down so only the " +
                        "launcher shade shows. Reversible; the vendor status bar returns " +
                        "fully after a reboot when turned off."
                else "Needs root — the vendor bars can't be suppressed on this build without it.",
                checked = settings.replaceSystemBars,
                onChange = settingsStore::setReplaceSystemBars,
                enabled = rootAvailable,
            )
        }

        // v2.8 — the reachability mirror (LAUNCHER_DESIGN §2.5).
        SettingsSection(title = "Reachability mirror") {
            Text(
                text = "Puts the quick-launch and radio column under the driver's hand. In a " +
                    "right-hand-drive car it swaps to the left of the screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            PickerSetting(
                label = "Driver's side",
                current = settings.driverSideMode,
                options = listOf(
                    DriverSideMode.AUTO to "Auto (from the car)",
                    DriverSideMode.LHD to "Left-hand drive",
                    DriverSideMode.RHD to "Right-hand drive",
                ),
                onSelect = settingsStore::setDriverSideMode,
            )
            CarTypeRow(controller = controller)
        }

        SettingsSection(title = "Day / night mode") {
            Text(
                text = "Auto follows the car's illumination signal. Force day or night to " +
                    "override the theme.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            PickerSetting(
                label = "Theme mode",
                current = settings.dayNightMode,
                options = listOf(
                    DayNightMode.AUTO to "Auto (follow car)",
                    DayNightMode.FORCE_DAY to "Force day",
                    DayNightMode.FORCE_NIGHT to "Force night",
                    DayNightMode.CLOCK to "Clock", // v2.7
                ),
                onSelect = settingsStore::setDayNightMode,
            )
        }

        // v2.7 — the clock stand-in for a missing illumination signal.
        SettingsSection(title = "Clock day / night") {
            Text(
                text = illuminationHint(carEvents),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            ToggleSetting(
                label = "Use the clock when the car says nothing",
                checked = settings.clockFallback,
                onChange = settingsStore::setClockFallback,
                description = "Only applies in Auto, and only until an illumination broadcast " +
                    "arrives. \"Clock\" mode above always uses these hours.",
            )
            SliderSetting(
                label = "Night starts",
                value = settings.nightStartHour,
                range = LauncherSettings.MIN_HOUR..LauncherSettings.MAX_HOUR,
                onChange = settingsStore::setNightStartHour,
                format = ::hourLabel,
            )
            SliderSetting(
                label = "Night ends",
                value = settings.nightEndHour,
                range = LauncherSettings.MIN_HOUR..LauncherSettings.MAX_HOUR,
                onChange = settingsStore::setNightEndHour,
                format = ::hourLabel,
            )
        }

        // v2.5 — the parked-only gate (LAUNCHER_DESIGN §1.4).
        SettingsSection(title = "Motion gating") {
            Text(
                text = "Hide search, the theme editor, the SysVar browser and destructive " +
                    "actions while the car is moving.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            ToggleSetting(
                "Enforce parked-only features",
                settings.motionGateEnabled,
                { on -> if (on) settingsStore.setMotionGateEnabled(true) else confirmGateOff = true },
            )
            Spacer(Modifier.size(12.dp))
            MotionStatusRow(carEvents = carEvents)
        }
    }

    if (confirmGateOff) {
        ConfirmDialog(
            title = "Disable parked-only gating?",
            message = "Search, the theme editor, the SysVar browser and destructive actions " +
                "will stay available while the car is moving.",
            confirmLabel = "Disable",
            destructive = true,
            onConfirm = { confirmGateOff = false; settingsStore.setMotionGateEnabled(false) },
            onDismiss = { confirmGateOff = false },
        )
    }
}

/**
 * v2.5 — live speed and verdict, so the gate is diagnosable on the bench.
 *
 * Without this the driver has no way to tell "the gate is open because I am parked" from "the
 * gate is open because GPS never got a fix" — the two look identical from the outside, and the
 * second is the one worth knowing about.
 */
@Composable
private fun MotionStatusRow(carEvents: CarEvents?) {
    if (carEvents == null) {
        return
    }
    val speed by carEvents.speedKmh.collectAsStateSafe(initial = GpsSpeedSource.SPEED_UNKNOWN)
    val motion by carEvents.motion.collectAsStateSafe(initial = CarEvents.Motion.UNKNOWN)

    val speedText = if (speed < 0) "no GPS fix" else "$speed km/h"
    val verdict = when (motion) {
        CarEvents.Motion.MOVING -> "moving — parked-only features hidden"
        CarEvents.Motion.PARKED -> "parked — everything available"
        CarEvents.Motion.UNKNOWN -> "unknown — gate open (fails open by design)"
    }

    Text(
        text = "Speed: $speedText · $verdict",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * v2.8 — the live raw `Sys_CarType`, next to the override that ignores it.
 *
 * Auto has nothing to go on. CAR_API §2.3 calls the key a "car model/type profile id" and the
 * value domain was never recovered, so [com.ripostelabs.carlauncher.data.Reachability] ships an empty
 * mapping table and Auto always answers LHD. Showing the raw value is the only honest thing this
 * row can do: it turns "Auto is wrong for me" into a value a user can report, which is what would
 * let the table stop being empty.
 */
@Composable
private fun CarTypeRow(controller: CarSettingsController?) {
    if (controller == null) {
        return
    }
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val carType = snapshot[SettingKeys.CAR_TYPE]

    InfoRow(
        label = "Car profile (Sys_CarType)",
        value = if (carType.isNullOrBlank()) "not set" else carType,
    )
    Text(
        text = "Auto cannot read a driver's side out of this value — no mapping was ever " +
            "recovered — so it stays left-hand drive. Set the override if that is wrong.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * v2.7 — say whether the car has ever actually reported illumination.
 *
 * This is the whole reason the fallback exists, and the one fact the driver cannot get anywhere
 * else: a launcher sitting in day colours looks the same whether the car said "day" or said
 * nothing at all. The same diagnosis-over-guesswork argument as the v2.5 motion status row.
 */
@Composable
private fun illuminationHint(carEvents: CarEvents?): String {
    if (carEvents == null) {
        return "Uses the hours below when the car's illumination signal is absent."
    }
    val seen by carEvents.illuminationSeen.collectAsStateSafe(initial = false)
    if (seen) {
        return "The car is reporting illumination, so Auto follows it and these hours are unused."
    }
    return "No illumination broadcast has arrived this session — the signal is permission-gated " +
        "and usually silent on a normal install, which is what these hours are for."
}

/** Whole hours only; a 24-position slider is already as fine as this control should get. */
private fun hourLabel(hour: Int): String = "%02d:00".format(hour)

@Composable
private fun ColumnChip(count: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(carShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "$count", style = MaterialTheme.typography.titleMedium, color = fg)
    }
}
