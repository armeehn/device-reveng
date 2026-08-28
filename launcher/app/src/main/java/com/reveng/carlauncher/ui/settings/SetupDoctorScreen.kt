package com.reveng.carlauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.DoctorCheck
import com.reveng.carlauncher.data.SetupDoctor
import com.reveng.carlauncher.ui.theme.carShape

/**
 * v0.4.2 — Setup Doctor screen. Shows every grant the launcher needs, repairs the failing ones
 * in-app when root is present, and otherwise prints the exact adb command per row. Exists because a
 * reinstall silently drops these grants and the resulting "nothing works" is indistinguishable from
 * a bug without a screen that names what's missing.
 */
@Composable
fun SetupDoctorScreen(
    controller: CarSettingsController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doctor = remember { SetupDoctor(context.applicationContext, scope) }
    val checks by doctor.checks.collectAsStateWithLifecycle()
    val repairing by doctor.repairing.collectAsStateWithLifecycle()
    val root by controller.rootAvailable.collectAsStateWithLifecycle()

    val passing = checks.count { it.ok }
    val total = checks.size
    val failing = checks.filter { !it.ok }
    val subtitle = when {
        total == 0 -> "Checking…"
        passing == total -> "All $total checks passing"
        else -> "$passing of $total passing — ${failing.size} need attention"
    }

    SettingsScaffold(title = "Setup doctor", onBack = onBack, subtitle = subtitle) {
        SettingsSection {
            when (root) {
                true -> if (failing.any { it.rootCommand != null }) {
                    ActionRow(
                        label = if (repairing) "Repairing…" else "Repair all with root",
                        description = "Grant the missing permissions and enable the listeners",
                        enabled = !repairing,
                        onClick = { doctor.repairAll() },
                    )
                } else {
                    Text(
                        text = "Root detected. Nothing to repair.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                false -> Text(
                    text = "Root not detected — run each command below over adb to repair.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                null -> Text(
                    text = "Checking for root…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ActionRow(
                label = "Re-check",
                onClick = { doctor.refresh() },
            )
        }

        SettingsSection(title = "Checks") {
            if (checks.isEmpty()) {
                Text(
                    text = "Checking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            checks.forEach { check ->
                DoctorCheckRow(check = check, showAdb = root != true)
            }
        }
    }
}

@Composable
private fun DoctorCheckRow(check: DoctorCheck, showAdb: Boolean) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (check.ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = if (check.ok) "OK" else "Needs attention",
                tint = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = check.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Only surface the raw adb command when the user can't just tap "Repair all" (no root).
        if (!check.ok && showAdb) {
            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(carShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { clipboard.setText(AnnotatedString(check.adbCommand)) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = check.adbCommand,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy command",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
