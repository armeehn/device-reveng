package com.reveng.carlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Movie // v2.7
import androidx.compose.material.icons.filled.Notifications // v2.7
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings // v0.6
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.CarService // v0.6
import com.reveng.carlauncher.data.SettingsStore // v0.6
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top status bar: live clock + car status chips (ACC power, day/night).
 * Data comes from [CarEvents] state flows (CAR_API §1.3, §6.3).
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
    // v2.7: the two parked-only shelves. Optional so previews and any un-wired caller still
    // compose; a null callback simply hides its icon rather than putting a dead one on screen.
    onOpenNotifications: (() -> Unit)? = null,
    onOpenContinueWatching: (() -> Unit)? = null,
) {
    val accOn by carEvents.accOn.collectAsStateSafe(initial = true)
    val dayNight by carEvents.dayNight.collectAsStateSafe(initial = CarEvents.DayNight.DAY)

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
                imageVector = if (dayNight == CarEvents.DayNight.NIGHT)
                    Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = "Illumination mode",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = "ACC power",
                tint = if (accOn) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier.size(28.dp),
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
                    .size(28.dp)
                    .clickable(onClick = onOpenThemes),
            )
            // v0.6: Quick Controls (volume/brightness/day-night/wifi-bt) pull-down button.
            if (carService != null && settingsStore != null) {
                QuickControlsButton(carService = carService, settingsStore = settingsStore)
            }
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

/** v2.7 — a status-bar entry point for a parked-only screen: dimmed and inert while moving. */
@Composable
private fun ShelfIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val locked = LocalParkedOnlyLock.current
    val tint = if (locked) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = LOCKED_ICON_ALPHA)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        imageVector = icon,
        contentDescription = if (locked) "$label — available when parked" else label,
        tint = tint,
        modifier = Modifier
            .size(28.dp)
            .clickable(enabled = !locked, onClick = onClick),
    )
}

/** Material's standard disabled-content opacity. */
private const val LOCKED_ICON_ALPHA = 0.38f

private fun nowString(): String =
    SimpleDateFormat("EEE  HH:mm", Locale.getDefault()).format(Date())
