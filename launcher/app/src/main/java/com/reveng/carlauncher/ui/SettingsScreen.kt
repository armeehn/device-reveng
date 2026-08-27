package com.reveng.carlauncher.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.data.DayNightMode
import com.reveng.carlauncher.data.LauncherSettings
import com.reveng.carlauncher.data.SettingsStore

/**
 * v0.6 — Settings screen. Launcher-level preferences persisted in [SettingsStore]:
 *   * "Set as default home" helper (opens [Settings.ACTION_HOME_SETTINGS] + explains),
 *   * app-grid density (column count feeding the [AppDrawer]),
 *   * which Home widgets show (media / radio / climate / nav), and
 *   * the day/night theme mode (auto / force-day / force-night).
 *
 * Navigation is the plain screen switch owned by MainActivity — no nav library, matching
 * [ThemesScreen].
 */
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings by settingsStore.settings.collectAsStateSafe(initial = LauncherSettings())

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- Header --------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", onClick = onBack)
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ---- Default home ---------------------------------------------
            SettingsSection(title = "Default home") {
                Text(
                    text = "This launcher registers as a HOME app, but Android still asks you to " +
                        "pick the default. Open home settings and choose \"Car Launcher\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                ActionButton(
                    icon = Icons.Filled.Home,
                    label = "Set as default home",
                    onClick = { openHomeSettings(context) },
                )
            }

            // ---- Grid density ---------------------------------------------
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

            // ---- Home widgets ---------------------------------------------
            SettingsSection(title = "Home widgets") {
                ToggleRow(
                    label = "Media card",
                    checked = settings.showMedia,
                    onChange = settingsStore::setShowMedia,
                )
                ToggleRow(
                    label = "Radio card",
                    checked = settings.showRadio,
                    onChange = settingsStore::setShowRadio,
                )
                ToggleRow(
                    label = "Climate readout",
                    checked = settings.showClimate,
                    onChange = settingsStore::setShowClimate,
                )
                ToggleRow(
                    label = "Navigation",
                    checked = settings.showNav,
                    onChange = settingsStore::setShowNav,
                )
            }

            // ---- Day / night ----------------------------------------------
            SettingsSection(title = "Day / night mode") {
                Text(
                    text = "Auto follows the car's illumination signal. Force day or night to " +
                        "override the theme.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("Auto", settings.dayNightMode == DayNightMode.AUTO) {
                        settingsStore.setDayNightMode(DayNightMode.AUTO)
                    }
                    ModeChip("Force day", settings.dayNightMode == DayNightMode.FORCE_DAY) {
                        settingsStore.setDayNightMode(DayNightMode.FORCE_DAY)
                    }
                    ModeChip("Force night", settings.dayNightMode == DayNightMode.FORCE_NIGHT) {
                        settingsStore.setDayNightMode(DayNightMode.FORCE_NIGHT)
                    }
                }
            }

            Spacer(Modifier.size(24.dp))
        }
    }
}

// ---- Building blocks -------------------------------------------------------

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ColumnChip(count: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "$count", style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

private fun openHomeSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        // Fallback: general settings if the home-settings panel isn't resolvable.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
