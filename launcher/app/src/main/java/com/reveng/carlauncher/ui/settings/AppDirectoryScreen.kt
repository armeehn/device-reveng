package com.reveng.carlauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.reveng.carlauncher.AppInfo
import com.reveng.carlauncher.AppRepository
import com.reveng.carlauncher.data.AppDirectoryStore
import com.reveng.carlauncher.data.Placement
import com.reveng.carlauncher.data.effectivePlacement
import com.reveng.carlauncher.ui.collectAsStateSafe
import com.reveng.carlauncher.ui.AppIcon
import com.reveng.carlauncher.ui.theme.carShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.4.2 — the custom app directory. Lists every launchable app with a Home / System / Hidden
 * selector that overrides [AppRepository]'s built-in user/system classification (which is a fixed
 * allow/deny list and can't be corrected per-unit). Writes go to [AppDirectoryStore]; the home
 * drawer observes the same flow and re-splits live.
 *
 * The current selection reflects the *effective* placement: an explicit override if the user set
 * one, otherwise the classification. "Reset to defaults" clears every override at once. Choosing a
 * segment that equals the default still stores it explicitly — harmless, and keeps the UI honest
 * about what the user last picked.
 */
@Composable
fun AppDirectoryScreen(
    onBack: () -> Unit,
    // The launcher-owned instance. Null falls back to a local one, which keeps previews working.
    directoryStore: AppDirectoryStore? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = directoryStore ?: remember { AppDirectoryStore(context.applicationContext, scope) }
    val placements by store.placements.collectAsStateSafe(initial = emptyMap())

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val repo = AppRepository(context)
        apps = withContext(Dispatchers.IO) { repo.loadApps() }
    }

    val overrideCount = placements.size
    val subtitle = if (overrideCount == 0) {
        "Move apps between Home and System, or hide them from the drawer"
    } else {
        "$overrideCount app${if (overrideCount == 1) "" else "s"} customised"
    }

    SettingsScaffold(title = "App directory", onBack = onBack, subtitle = subtitle) {
        if (overrideCount > 0) {
            ActionRow(
                label = "Reset to defaults",
                description = "Clear every override and go back to automatic placement",
                onClick = { scope.launch { store.clearAll() } },
            )
        }

        SettingsSection(title = "Apps") {
            if (apps.isEmpty()) {
                Text(
                    text = "Loading apps…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            apps.forEach { app ->
                val effective = app.effectivePlacement(placements)
                AppDirectoryRow(
                    app = app,
                    selected = effective,
                    onSelect = { placement -> scope.launch { store.setPlacement(app.packageName, placement) } },
                )
            }
        }
    }
}

@Composable
private fun AppDirectoryRow(
    app: AppInfo,
    selected: Placement,
    onSelect: (Placement) -> Unit,
) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app = app, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        PlacementSegments(selected = selected, onSelect = onSelect)
    }
}

/** Three-way Home / System / Hidden selector, styled from the CarTheme palette. */
@Composable
private fun PlacementSegments(
    selected: Placement,
    onSelect: (Placement) -> Unit,
) {
    val shape = carShape(12.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Placement.entries.forEach { placement ->
            val isSelected = placement == selected
            Box(
                modifier = Modifier
                    .clip(carShape(12.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(placement) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = placement.label(),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Placement.label(): String = when (this) {
    Placement.HOME -> "Home"
    Placement.SYSTEM -> "System"
    Placement.HIDDEN -> "Hidden"
}
