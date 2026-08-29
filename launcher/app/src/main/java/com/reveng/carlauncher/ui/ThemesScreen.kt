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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.reveng.carlauncher.ui.theme.carCard
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload // v2.7
import androidx.compose.material.icons.filled.FileUpload // v2.7
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext // v2.7
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog // v2.7
import com.reveng.carlauncher.data.ThemeTransfer // v2.7
import com.reveng.carlauncher.ui.settings.DialogTextButton // v2.7
import com.reveng.carlauncher.ui.theme.CarTheme
import com.reveng.carlauncher.ui.theme.ThemeColors
import com.reveng.carlauncher.ui.theme.ThemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File // v2.7

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
    onImport: (CarTheme) -> Unit = {}, // v2.7
) {
    // v2.7 — theme import/export. The files live on external storage, where a stat can block for
    // tens of milliseconds on this unit's eMMC, so every read and write below runs on
    // Dispatchers.IO: the listing via produceState (keyed on the dialog, not on recomposition) and
    // the export/import from the click handler's own scope.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val importable by produceState(initialValue = emptyList<File>(), importing) {
        value = if (importing) withContext(Dispatchers.IO) { ThemeTransfer.listImportable(context) }
        else emptyList()
    }

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
            IconTile(
                icon = Icons.Filled.FileDownload,
                label = "Import theme",
                onClick = { importing = true },
            )
            Spacer(Modifier.width(8.dp))
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
                    onExport = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                ThemeTransfer.export(context, theme)
                            }
                            message = if (file != null) "Exported to ${file.absolutePath}"
                            else "Export failed — external storage unavailable."
                        }
                    },
                )
            }
        }
    }

    if (importing) {
        ImportThemeDialog(
            files = importable,
            onPick = { file ->
                importing = false
                scope.launch {
                    val theme = withContext(Dispatchers.IO) { ThemeTransfer.import(file) }
                    if (theme == null) {
                        message = "${file.name} is not a readable theme file."
                    } else {
                        onImport(theme)
                    }
                }
            },
            onDismiss = { importing = false },
        )
    }

    message?.let { text ->
        MessageDialog(text = text, onDismiss = { message = null })
    }
}

/**
 * v2.7 — pick a theme file to import.
 *
 * A plain list of what is in the directory rather than a system file picker: the vendor's document
 * UI is another un-theme-able system screen, and the only files that can be here are ones the owner
 * pushed. When the list is empty the dialog's job is to say *where* to put a file, since that is
 * the only thing the driver could possibly be missing.
 */
@Composable
private fun ImportThemeDialog(files: List<File>, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .carCard()
                .clip(carShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Text(
                text = "Import theme",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            if (files.isEmpty()) {
                Text(
                    text = "No theme files found. Push one to " +
                        "${ThemeTransfer.directory(context)?.absolutePath ?: "external storage"} " +
                        "and reopen this dialog.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                files.forEach { file ->
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(carShape(12.dp))
                            .clickable { onPick(file) }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            DialogTextButton("Close", onDismiss, filled = false, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** One-line outcome report (an exported path, a rejected file). Dismiss is the only action. */
@Composable
private fun MessageDialog(text: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .carCard()
                .clip(carShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(20.dp))
            DialogTextButton("OK", onDismiss, filled = true, modifier = Modifier.fillMaxWidth())
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
    onExport: () -> Unit, // v2.7
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
            style = theme.style,
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
            // v2.7: built-ins export too — the fastest way to hand-author a theme is to pull a
            // preset, edit the hex values on a real keyboard, and push it back.
            IconTile(icon = Icons.Filled.FileUpload, label = "Export", onClick = onExport)
            if (!theme.isBuiltIn) {
                IconTile(icon = Icons.Filled.Edit, label = "Edit", onClick = onEdit)
                IconTile(icon = Icons.Filled.Delete, label = "Delete", onClick = onDelete)
            }
        }
    }
}

/**
 * A miniature mock of the launcher (background → surface card → accent chips + text lines)
 * rendered directly from a [ThemeColors] variant. Shared by the Themes list and the live
 * preview in the editor.
 *
 * Corners come from the PREVIEWED theme's [ThemeStyle] via literal [RoundedCornerShape]s —
 * carShape() would apply the *active* theme's corner scale to another theme's preview. The
 * mock card always draws a thin outline: themes where surface == background (Riposte's
 * bone-on-bone, whose real cards separate with their own ink border) would otherwise
 * preview as a blank rectangle.
 */
@Composable
fun ThemePreviewSwatch(
    colors: ThemeColors,
    modifier: Modifier = Modifier,
    style: ThemeStyle = ThemeStyle(),
) {
    fun shape(radius: Dp) = RoundedCornerShape(radius * style.cornerScale)
    fun accentChip(argb: Long, modifier: Modifier) =
        modifier
            .size(16.dp)
            .clip(shape(4.dp))
            .background(Color(argb))

    Box(
        modifier = modifier
            .clip(shape(12.dp))
            .background(Color(colors.background))
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape(8.dp))
                .background(Color(colors.surface))
                .border(
                    width = if (style.hardEdge) 2.dp else 1.dp,
                    color = if (style.hardEdge) Color(colors.onBackground)
                    else Color(colors.onSurfaceMuted).copy(alpha = SWATCH_OUTLINE_ALPHA),
                    shape = shape(8.dp),
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(modifier = accentChip(colors.primary, Modifier))
                // The optional accent trio, only when the theme actually sets it.
                if (colors.accent2 != 0L) {
                    Box(modifier = accentChip(colors.accent2, Modifier))
                }
                if (colors.accent3 != 0L) {
                    Box(modifier = accentChip(colors.accent3, Modifier))
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .height(8.dp)
                        .width(70.dp)
                        .clip(shape(4.dp))
                        .background(Color(colors.onSurface)),
                )
            }
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .fillMaxWidth()
                    .clip(shape(3.dp))
                    .background(Color(colors.onSurfaceMuted)),
            )
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(90.dp)
                    .clip(shape(3.dp))
                    .background(Color(colors.surfaceVariant)),
            )
        }
    }
}

/** Same weight the theme system gives ColorScheme.outline (onSurfaceMuted at half alpha). */
private const val SWATCH_OUTLINE_ALPHA = 0.5f

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
