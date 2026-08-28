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
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.data.CarSettingsController

/**
 * v2.0 — All settings (advanced): a raw browser over the *live* vendor SysVar table. Unlike the
 * category screens (which curate known keys with friendly controls), this enumerates every
 * `keyname` the provider currently holds — so nothing the vendor stores is hidden from the UI,
 * even keys we haven't catalogued. Search narrows the list; tapping a row edits the raw value.
 *
 * Writing still needs root / a privileged install (CAR_API §2.2); a failed write rolls back and
 * the value snaps back to what the provider reports.
 */
@Composable
fun AdvancedSettingsScreen(
    controller: CarSettingsController,
    onBack: () -> Unit,
) {
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }

    val rows = remember(snap, query) {
        // SysVar.readAll() drops null keynames, so keys are safe to sort/search here.
        snap.entries
            .filter { query.isBlank() || it.key.contains(query, ignoreCase = true) }
            .sortedBy { it.key.lowercase() }
    }

    SettingsScaffold(
        title = "All settings",
        onBack = onBack,
        subtitle = "${snap.size} vendor keys",
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search keys…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        if (snap.isEmpty()) {
            Text(
                text = "Reading the vendor settings store…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SettingsSection {
                if (rows.isEmpty()) {
                    Text(
                        text = "No keys match \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    rows.forEach { (k, v) ->
                        RawRow(key = k, value = v, onClick = { editing = k to v })
                    }
                }
            }
        }
    }

    editing?.let { (key, value) ->
        RawEditDialog(
            key = key,
            initial = value,
            onSave = { controller.setString(key, it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun RawRow(key: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = key,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text = value.ifBlank { "\"\"" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RawEditDialog(
    key: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(carShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Text(
                text = key,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Spacer(Modifier.size(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DialogTextButton("Cancel", onDismiss, filled = false, modifier = Modifier.weight(1f))
                DialogTextButton("Save", { onSave(text) }, filled = true, modifier = Modifier.weight(1f))
            }
        }
    }
}
