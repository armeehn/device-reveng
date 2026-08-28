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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.CarService // v0.6
import com.reveng.carlauncher.data.DayNightMode
import com.reveng.carlauncher.data.LauncherSettings
import com.reveng.carlauncher.data.SettingsStore // v0.6
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
) {
    val accOn by carEvents.accOn.collectAsStateSafe(initial = true)
    val dayNight by carEvents.dayNight.collectAsStateSafe(initial = CarEvents.DayNight.DAY)
    val settings by (settingsStore?.settings?.collectAsStateSafe(initial = LauncherSettings())
        ?: remember { mutableStateOf(LauncherSettings()) })

    // Effective night state: the forced day/night mode wins over the car illumination
    // signal — same resolution MainActivity uses to pick the theme, so the icon always
    // matches what's on screen.
    val carNight = dayNight == CarEvents.DayNight.NIGHT
    val night = when (settings.dayNightMode) {
        DayNightMode.FORCE_DAY -> false
        DayNightMode.FORCE_NIGHT -> true
        DayNightMode.AUTO -> carNight
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
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (night) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = "Toggle day/night theme",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
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
                    },
            )
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = "Power & sleep",
                tint = if (accOn) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onOpenPowerSettings),
            )
            Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = "Themes",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onOpenThemes),
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
                    .size(28.dp)
                    .clickable(onClick = withTapFeedback(onOpenDashboard)),
            )
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = "Driver profiles",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = withTapFeedback(onOpenProfiles)),
            )
            // v0.6: Settings gear -> SettingsScreen.
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onOpenSettings),
            )
        }
    }
}

private fun nowString(): String =
    SimpleDateFormat("EEE  HH:mm", Locale.getDefault()).format(Date())
