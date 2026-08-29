package com.reveng.carlauncher.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.ui.theme.JetBrainsMono
import com.reveng.carlauncher.data.LauncherBackup
import com.reveng.carlauncher.ui.theme.carShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.4.2 — launcher state backup & restore. Snapshots every DataStore file into the external files
 * dir and restores a chosen snapshot (with a restart). See [LauncherBackup] for why it is a
 * file-level copy rather than per-store serialisation, and why restore restarts the app.
 */
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backups by remember { mutableStateOf<List<File>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<File?>(null) }

    fun reload() {
        scope.launch { backups = withContext(Dispatchers.IO) { LauncherBackup.list(context) } }
    }
    LaunchedEffect(Unit) { reload() }

    val pkg = context.packageName
    val subtitle = "Save & restore themes, favourites, app order, directory & profiles"

    SettingsScaffold(title = "Backup & restore", onBack = onBack, subtitle = subtitle) {
        SettingsSection {
            ActionRow(
                label = if (busy) "Working…" else "Create backup",
                description = "Snapshot the current launcher state",
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        withContext(Dispatchers.IO) { LauncherBackup.create(context, System.currentTimeMillis()) }
                        busy = false
                        reload()
                    }
                },
            )
            Text(
                text = "Pull/push over adb:\n" +
                    "adb pull /sdcard/Android/data/$pkg/files/backups/",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection(title = "Backups") {
            if (backups.isEmpty()) {
                Text(
                    text = "No backups yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            backups.forEach { backup ->
                BackupRow(
                    backup = backup,
                    onRestore = { restoreTarget = backup },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) { LauncherBackup.delete(backup) }
                            reload()
                        }
                    },
                )
            }
        }
    }

    val target = restoreTarget
    if (target != null) {
        ConfirmDialog(
            title = "Restore this backup?",
            message = "This replaces the current launcher state with the backup and restarts the launcher.",
            confirmLabel = "Restore & restart",
            destructive = true,
            onDismiss = { restoreTarget = null },
            onConfirm = {
                restoreTarget = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { LauncherBackup.restore(context, target) }
                    if (ok) LauncherBackup.restartApp(context)
                }
            },
        )
    }
}

@Composable
private fun BackupRow(
    backup: File,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val label = remember(backup) {
        LauncherBackup.timestampOf(backup)?.let {
            SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(it))
        } ?: backup.name
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Restore",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(carShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onRestore)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "Delete backup",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clip(carShape(10.dp))
                .clickable(onClick = onDelete)
                .padding(8.dp),
        )
    }
}
