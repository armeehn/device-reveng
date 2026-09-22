package com.ripostelabs.carlauncher.ui.settings

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
import com.ripostelabs.carlauncher.ui.theme.JetBrainsMono
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.SysVarExport
import com.ripostelabs.carlauncher.ui.collectAsStateSafe
import com.ripostelabs.carlauncher.ui.theme.carShape
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
    // export() returns null on a failed write; dropping it made a full or read-only /sdcard
    // look like a dead button.
    var failure by remember { mutableStateOf<String?>(null) }
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
                        val written = withContext(Dispatchers.IO) {
                            SysVarExport.export(context, snapshot, System.currentTimeMillis())
                        }
                        failure = if (written == null) EXPORT_FAILED else null
                        busy = false
                        reload()
                    }
                },
            )
            val message = failure
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
                .size(DELETE_TARGET_DP.dp)
                .clip(carShape(10.dp))
                .clickable(onClick = onDelete)
                .padding(12.dp),
        )
    }
}

/** Destructive tap on a list row; kept on the 48 dp floor rather than the 40 dp glyph box. */
private const val DELETE_TARGET_DP = 48

/** An export write that did not happen. Same cause as the backup writer's: no room, or no write. */
private const val EXPORT_FAILED = "Could not write the export. Storage may be full or read-only."
