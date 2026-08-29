package com.reveng.carlauncher.ui.settings

import androidx.compose.foundation.clickable
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
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.SysVarExport
import com.reveng.carlauncher.ui.collectAsStateSafe
import com.reveng.carlauncher.ui.theme.carShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.4.7 - export the live SysVar table to a JSON file. See [SysVarExport] for the why.
 */
@Composable
fun SysVarExportScreen(
    controller: CarSettingsController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by controller.snapshot.collectAsStateSafe(initial = emptyMap())
    var exports by remember { mutableStateOf<List<File>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    fun reload() {
        scope.launch { exports = withContext(Dispatchers.IO) { SysVarExport.list(context) } }
    }
    LaunchedEffect(Unit) {
        controller.refresh()
        reload()
    }

    val pkg = context.packageName
    SettingsScaffold(
        title = "SysVar export",
        subtitle = snapshot.size.toString() + " keys in the live table",
        onBack = onBack,
    ) {
        SettingsSection {
            ActionRow(
                label = if (busy) "Exporting..." else "Export snapshot",
                description = "Write the whole live SysVar table to a JSON file",
                enabled = !busy && snapshot.isNotEmpty(),
                onClick = {
                    scope.launch {
                        busy = true
                        withContext(Dispatchers.IO) {
                            SysVarExport.export(context, snapshot, System.currentTimeMillis())
                        }
                        busy = false
                        reload()
                    }
                },
            )
            Text(
                text = "Pull over adb:\nadb pull /sdcard/Android/data/" + pkg + "/files/sysvar-dumps/",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection(title = "Exports") {
            if (exports.isEmpty()) {
                Text(
                    text = "No exports yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            exports.forEach { file ->
                ExportRow(
                    file = file,
                    // v0.4.7 — confirmed below: a one-tap permanent delete was a mis-tap away.
                    onDelete = { deleteTarget = file },
                )
            }
        }
    }

    val doomed = deleteTarget
    if (doomed != null) {
        ConfirmDialog(
            title = "Delete this export?",
            message = "The export file is removed. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) { SysVarExport.delete(doomed) }
                    reload()
                }
            },
        )
    }
}

@Composable
private fun ExportRow(file: File, onDelete: () -> Unit) {
    val label = remember(file) {
        SysVarExport.timestampOf(file)?.let {
            SimpleDateFormat("MMM d, yyyy - HH:mm", Locale.getDefault()).format(Date(it))
        } ?: file.name
    }
    val size = remember(file) { (file.length() / 1024L).toString() + " KB" }
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
            text = size,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "Delete export",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clip(carShape(10.dp))
                .clickable(onClick = onDelete)
                .padding(8.dp),
        )
    }
}

