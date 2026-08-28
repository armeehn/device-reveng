package com.reveng.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.reveng.carlauncher.input.focusRing // v2.8
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.ui.theme.CarTheme
import com.reveng.carlauncher.ui.theme.ThemeColors

/**
 * Themes screen (LAUNCHER_DESIGN v0.5): lists built-in presets and user themes with a
 * live preview swatch and per-theme actions — set active, duplicate, and (user themes
 * only) edit / delete. "New theme" duplicates the current theme into the editor.
 *
 * Navigation is a plain screen switch owned by MainActivity — no nav library.
 */
@Composable
fun ThemesScreen(
    themes: List<CarTheme>,
    activeId: String,
    night: Boolean,
    onSetActive: (CarTheme) -> Unit,
    onDuplicate: (CarTheme) -> Unit,
    onEdit: (CarTheme) -> Unit,
    onDelete: (CarTheme) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", onClick = onBack)
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Themes",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            IconTile(icon = Icons.Filled.Add, label = "New theme", onClick = onNew, filled = true)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(themes, key = { it.id }) { theme ->
                ThemeCard(
                    theme = theme,
                    isActive = theme.id == activeId,
                    night = night,
                    onSetActive = { onSetActive(theme) },
                    onDuplicate = { onDuplicate(theme) },
                    onEdit = { onEdit(theme) },
                    onDelete = { onDelete(theme) },
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: CarTheme,
    isActive: Boolean,
    night: Boolean,
    onSetActive: () -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderColor =
        if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .clip(carShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(if (isActive) 2.dp else 1.dp, borderColor, carShape(18.dp))
            .focusRing(cornerRadiusDp = 18)
            .clickable(onClick = onSetActive) // v2.8 ring
            .padding(14.dp),
    ) {
        ThemePreviewSwatch(
            colors = theme.variant(night),
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = theme.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = if (theme.isBuiltIn) "Built-in" else "Custom",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(icon = Icons.Filled.ContentCopy, label = "Duplicate", onClick = onDuplicate)
            if (!theme.isBuiltIn) {
                IconTile(icon = Icons.Filled.Edit, label = "Edit", onClick = onEdit)
                IconTile(icon = Icons.Filled.Delete, label = "Delete", onClick = onDelete)
            }
        }
    }
}

/**
 * A miniature mock of the launcher (background → surface card → accent chip + text lines)
 * rendered directly from a [ThemeColors] variant. Shared by the Themes list and the live
 * preview in the editor.
 */
@Composable
fun ThemePreviewSwatch(colors: ThemeColors, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(carShape(12.dp))
            .background(Color(colors.background))
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(carShape(8.dp))
                .background(Color(colors.surface))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(carShape(4.dp))
                        .background(Color(colors.primary)),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .height(8.dp)
                        .width(70.dp)
                        .clip(carShape(4.dp))
                        .background(Color(colors.onSurface)),
                )
            }
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .fillMaxWidth()
                    .clip(carShape(3.dp))
                    .background(Color(colors.onSurfaceMuted)),
            )
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(90.dp)
                    .clip(carShape(3.dp))
                    .background(Color(colors.surfaceVariant)),
            )
        }
    }
}

/** Small square icon button used across the Themes/editor UI. */
@Composable
fun IconTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    filled: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(carShape(12.dp))
            .background(
                if (filled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .focusRing()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (filled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}
