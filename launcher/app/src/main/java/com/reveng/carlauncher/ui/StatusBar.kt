package com.reveng.carlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle // v3.0
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Movie // v2.7
import androidx.compose.material.icons.filled.Notifications // v2.7
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings // v0.6
import androidx.compose.material.icons.filled.Speed // v3.0
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.CarService // v0.6
import com.reveng.carlauncher.data.DayNightMode
import com.reveng.carlauncher.data.LauncherSettings
import com.reveng.carlauncher.data.SettingsStore // v0.6
import com.reveng.carlauncher.ui.theme.DISABLED_ALPHA
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top status bar: live clock + car status chips (ACC power, day/night).
 * Data comes from [CarEvents] state flows (CAR_API §1.3, §6.3).
 *
 * The sun/moon chip toggles the day/night theme ([SettingsStore.setDayNightMode]); the
 * power chip stays an ACC status light but opens Settings → Power & sleep on tap.
 */
@Composable
fun StatusBar(
    carEvents: CarEvents,
    modifier: Modifier = Modifier,
    onOpenThemes: () -> Unit = {},
    // v0.6: Settings gear + Quick Controls affordances (all optional/additive).
    onOpenSettings: () -> Unit = {},
    carService: CarService? = null,
    settingsStore: SettingsStore? = null,
    onOpenPowerSettings: () -> Unit = {},
    // v3.0: cockpit dashboard + driver-profile switcher.
    onOpenDashboard: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    // v2.7: the two parked-only shelves. Optional so previews and any un-wired caller still
    // compose; a null callback simply hides its icon rather than putting a dead one on screen.
    onOpenNotifications: (() -> Unit)? = null,
    onOpenContinueWatching: (() -> Unit)? = null,
) {
    val accOn by carEvents.accOn.collectAsStateSafe(initial = true)
    val dayNight by carEvents.dayNight.collectAsStateSafe(initial = CarEvents.DayNight.DAY)
    val settings by (settingsStore?.settings?.collectAsStateSafe(initial = LauncherSettings())
        ?: remember { mutableStateOf(LauncherSettings()) })

    // Effective night state: the forced day/night mode wins over the car illumination
    // signal — same resolution MainActivity uses to pick the theme, so the icon always
    // matches what's on screen. v2.7 adds CLOCK + the AUTO clock-fallback; mirror both here.
    val carNight = dayNight == CarEvents.DayNight.NIGHT
    val illuminationSeen by carEvents.illuminationSeen.collectAsStateSafe(initial = false)
    val clockNight by rememberClockNight(settings.nightStartHour, settings.nightEndHour)
    val night = when (settings.dayNightMode) {
        DayNightMode.FORCE_DAY -> false
        DayNightMode.FORCE_NIGHT -> true
        DayNightMode.CLOCK -> clockNight
        DayNightMode.AUTO ->
            if (settings.clockFallback && !illuminationSeen) clockNight
            else carNight
    }

    val time by produceState(initialValue = nowString()) {
        while (true) {
            value = nowString()
            delay(1_000)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Vertical padding drops 12 → 8 so the 48 dp icon targets and the 36 sp clock
            // keep the strip at roughly its old height.
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = time,
            // §2.1 asks for a 40 sp clock; 36 sp is the most the strip fits without growing
            // into the content band below (documented deviation).
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = CLOCK_SP.sp),
            color = MaterialTheme.colorScheme.onBackground,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v3.1: always-visible Wi-Fi / BT / volume / brightness chips. Display-only;
            // tapping the group opens the same Quick Controls panel as the Tune icon, and
            // closing it bumps refreshKey so the volume/brightness chips re-read at once.
            var quickOpen by remember { mutableStateOf(false) }
            var quickClosed by remember { mutableIntStateOf(0) }
            StatusIndicators(
                carService = carService,
                carEvents = carEvents, // v0.4.9 vendor BT status
                refreshKey = quickClosed,
                onOpen = if (carService != null && settingsStore != null) {
                    { quickOpen = true }
                } else {
                    null
                },
            )
            if (carService != null && settingsStore != null) {
                QuickControlsDialogHost(
                    open = quickOpen,
                    onDismiss = {
                        quickOpen = false
                        quickClosed++
                    },
                    carService = carService,
                    settingsStore = settingsStore,
                )
            }

            Icon(
                imageVector = if (night) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = "Toggle day/night theme",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(STRIP_TARGET_DP.dp)
                    .clickable(enabled = settingsStore != null) {
                        // Flip the theme. A flip that lands back on what the car signal
                        // already says becomes AUTO, so the launcher resumes following
                        // the car instead of staying pinned to a forced mode.
                        val target = !night
                        settingsStore?.setDayNightMode(
                            when {
                                target == carNight -> DayNightMode.AUTO
                                target -> DayNightMode.FORCE_NIGHT
                                else -> DayNightMode.FORCE_DAY
                            }
                        )
                    }
                    .padding(STRIP_ICON_PAD_DP.dp),
            )
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = "Power & sleep",
                tint = if (accOn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
                modifier = Modifier
                    .size(STRIP_TARGET_DP.dp)
                    .clickable(onClick = onOpenPowerSettings)
                    .padding(STRIP_ICON_PAD_DP.dp),
            )
            // v2.7 shelves. Both screens are parked-only, so the icons are dimmed and inert while
            // the car is moving instead of vanishing — a status bar that reflows at every red
            // light is its own distraction.
            if (onOpenNotifications != null) {
                ShelfIcon(
                    icon = Icons.Filled.Notifications,
                    label = "Notifications",
                    onClick = onOpenNotifications,
                )
            }
            if (onOpenContinueWatching != null) {
                ShelfIcon(
                    icon = Icons.Filled.Movie,
                    label = "Continue watching",
                    onClick = onOpenContinueWatching,
                )
            }
            Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = "Themes",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(STRIP_TARGET_DP.dp)
                    .clickable(onClick = onOpenThemes)
                    .padding(STRIP_ICON_PAD_DP.dp),
            )
            // v0.6: Quick Controls (volume/brightness/day-night/wifi-bt) pull-down button.
            if (carService != null && settingsStore != null) {
                QuickControlsButton(carService = carService, settingsStore = settingsStore)
            }
            // v3.0: the cockpit dashboard and the driver-profile switcher. Both live here so
            // each is exactly two taps from Home, which is the §3.0 requirement for profiles.
            Icon(
                imageVector = Icons.Filled.Speed,
                contentDescription = "Vehicle dashboard",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(STRIP_TARGET_DP.dp)
                    .clickable(onClick = withTapFeedback(onOpenDashboard))
                    .padding(STRIP_ICON_PAD_DP.dp),
            )
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = "Driver profiles",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(STRIP_TARGET_DP.dp)
                    .clickable(onClick = withTapFeedback(onOpenProfiles))
                    .padding(STRIP_ICON_PAD_DP.dp),
            )
            // v0.6: Settings gear -> SettingsScreen.
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(STRIP_TARGET_DP.dp)
                    .clickable(onClick = onOpenSettings)
                    .padding(STRIP_ICON_PAD_DP.dp),
            )
        }
    }
}

/** v2.7 — a status-bar entry point for a parked-only screen: dimmed and inert while moving. */
@Composable
private fun ShelfIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val locked = LocalParkedOnlyLock.current
    val tint = if (locked) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        imageVector = icon,
        contentDescription = if (locked) "$label — available when parked" else label,
        tint = tint,
        modifier = Modifier
            .size(STRIP_TARGET_DP.dp)
            .clickable(enabled = !locked, onClick = onClick)
            .padding(STRIP_ICON_PAD_DP.dp),
    )
}


/**
 * Status-strip geometry. §1.2's 76 dp target cannot fit a 40 dp strip; every icon instead
 * gets the 48 dp Material minimum as its clickable node, drawn at 28 dp inside it.
 * Clock: §2.1 asks 40 sp; 36 sp is the ceiling before the strip eats the content band.
 */
private const val CLOCK_SP = 36
private const val STRIP_TARGET_DP = 48
private const val STRIP_ICON_PAD_DP = 10

private fun nowString(): String =
    SimpleDateFormat("EEE  HH:mm", Locale.getDefault()).format(Date())
