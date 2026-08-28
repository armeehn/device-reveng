package com.reveng.carlauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.reveng.carlauncher.HomeRole
import com.reveng.carlauncher.data.DayNightMode
import com.reveng.carlauncher.data.LauncherSettings
import com.reveng.carlauncher.data.SettingsStore
import com.reveng.carlauncher.ui.collectAsStateSafe

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
) {
    val context = LocalContext.current
    val settings by settingsStore.settings.collectAsStateSafe(initial = LauncherSettings())

    var isDefaultHome by remember { mutableStateOf(HomeRole.isDefaultHome(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefaultHome = HomeRole.isDefaultHome(context)
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
                ),
                onSelect = settingsStore::setDayNightMode,
            )
        }
    }
}

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
