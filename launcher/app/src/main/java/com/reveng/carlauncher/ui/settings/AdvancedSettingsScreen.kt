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
import androidx.compose.material3.Text
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
import com.reveng.carlauncher.data.ProtectedSettingKeys // v0.4.3.8
import com.reveng.carlauncher.ui.keyboard.CarTextField // v2.7
import com.reveng.carlauncher.ui.keyboard.CommitMode // v2.7

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
        // v2.7: our own keyboard. This screen is the reason it grew shift and symbols — vendor
        // key names are mixed-case with underscores, and their values are arbitrary strings.
        CarTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search keys…",
            commit = CommitMode.LIVE,
            modifier = Modifier.fillMaxWidth(),
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
                        // v0.4.3.8: a handful of keys can leave the unit unusable — see
                        // ProtectedSettingKeys. Those rows still show their live value, but they
                        // are read-only and say why.
                        val lockedReason = ProtectedSettingKeys.reasonFor(k)
                        RawRow(
                            key = k,
                            value = v,
                            lockedReason = lockedReason,
                            onClick = { if (lockedReason == null) editing = k to v },
                        )
                    }
                }
            }
        }
    }

    editing?.let { (key, value) ->
        RawEditDialog(
            key = key,
            initial = value,
            // v0.4.3.8: the row is not clickable when protected, but the *write* is the thing that
            // must never happen, so it is refused here too rather than only in the UI above.
            onSave = {
                if (!ProtectedSettingKeys.isProtected(key)) controller.setString(key, it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun RawRow(key: String, value: String, lockedReason: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = lockedReason == null, onClick = onClick)
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
            if (lockedReason != null) {
                Text(
                    text = "Read-only — $lockedReason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
            // v2.7: ON_DONE, unlike the search box above. Every commit here is a write to live
            // vehicle config through a root shell; emitting one per keystroke would put a
            // half-typed value into the SysVar table on its way to the real one.
            CarTextField(
                value = text,
                onValueChange = { text = it },
                label = "Value",
                commit = CommitMode.ON_DONE,
                modifier = Modifier.fillMaxWidth(),
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
