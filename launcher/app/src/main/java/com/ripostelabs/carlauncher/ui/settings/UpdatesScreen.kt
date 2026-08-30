package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.BuildConfig
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.data.UpdateController
import com.ripostelabs.carlauncher.data.UpdateStatus
import com.ripostelabs.carlauncher.ui.LocalParkedOnlyLock
import com.ripostelabs.carlauncher.ui.keyboard.CarTextField
import com.ripostelabs.carlauncher.ui.keyboard.CommitMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.7 — Settings ▸ Updates: the auto-updater's face.
 *
 * Everything here is a readout of [UpdateController.status] plus four inputs: check now,
 * install, the two auto toggles, and the GitHub token. The install action sits behind a
 * [ConfirmDialog] and the parked-only lock — not because installing is destructive (it
 * isn't; data survives a `pm install -r`), but because a successful install kills and
 * relaunches the HOME app, and that blink belongs in a parked car.
 */
@Composable
fun UpdatesScreen(
    updater: UpdateController,
    onBack: () -> Unit,
) {
    val settings by updater.settings.collectAsStateWithLifecycle()
    val status by updater.status.collectAsStateWithLifecycle()
    val parkedLock = LocalParkedOnlyLock.current

    // Same probe as LauncherPrefsScreen: null = probing, shown as its own tri-state row.
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        rootAvailable = withContext(Dispatchers.IO) { RootShell.isRootAvailable() }
    }

    var confirmInstall by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = "Updates",
        onBack = onBack,
        subtitle = "Pulls tagged CI builds from GitHub",
    ) {
        SettingsSection(title = "This build") {
            InfoRow("Installed", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("Root shell", when (rootAvailable) {
                null -> "Probing…"
                true -> "Available"
                false -> "Not available — install disabled"
            })
        }

        SettingsSection(title = "Latest release") {
            StatusRows(status, settings.lastCheckMillis)
            ActionRow(
                label = "Check now",
                description = "Ask GitHub for the newest tagged build",
                enabled = !status.busy,
                onClick = updater::checkNow,
            )
            val available = status as? UpdateStatus.Available
            ActionRow(
                label = available?.let { "Install ${it.release.versionName}" } ?: "Install update",
                description = when {
                    available == null -> "Enabled once a newer build is found"
                    parkedLock -> "Available when parked"
                    else -> "Downloads, verifies SHA-256, then pm install -r; the launcher restarts"
                },
                enabled = available != null && !parkedLock && rootAvailable == true,
                onClick = { confirmInstall = true },
            )
        }

        SettingsSection(title = "Automatic") {
            ToggleSetting(
                label = "Check at startup",
                description = "Once a day, when the launcher starts",
                checked = settings.autoCheck,
                onChange = updater::setAutoCheck,
            )
            ToggleSetting(
                label = "Install without asking",
                description = "A found update installs itself at startup and restarts the launcher",
                checked = settings.autoInstall,
                enabled = settings.autoCheck,
                onChange = updater::setAutoInstall,
            )
        }

        SettingsSection(title = "GitHub token (optional)") {
            Text(
                text = "Releases come from the public carlauncher-releases repo, so no " +
                    "token is needed. Set one only if anonymous checks hit GitHub's " +
                    "rate limit. To skip the car keyboard: adb push token.txt " +
                    "/sdcard/Android/data/${BuildConfig.APPLICATION_ID}/files/updates/github-token.txt " +
                    "— it is imported and deleted on the next check.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            CarTextField(
                value = settings.token,
                onValueChange = updater::setToken,
                label = "Token",
                placeholder = "github_pat_…",
                commit = CommitMode.ON_DONE,
            )
        }
    }

    if (confirmInstall) {
        val release = (status as? UpdateStatus.Available)?.release
        ConfirmDialog(
            title = "Install update?",
            message = release?.let {
                "${it.versionName} (versionCode ${it.versionCode}) will be downloaded, " +
                    "verified and installed over the running launcher. Settings and themes " +
                    "are kept. The screen will go dark for a moment while HOME restarts."
            } ?: "",
            confirmLabel = "Install",
            onConfirm = {
                confirmInstall = false
                updater.installLatest()
            },
            onDismiss = { confirmInstall = false },
        )
    }
}

/** The state machine rendered as info rows; one line of truth per state. */
@Composable
private fun StatusRows(status: UpdateStatus, lastCheckMillis: Long) {
    when (status) {
        UpdateStatus.Idle -> InfoRow(
            "Status",
            if (lastCheckMillis == 0L) "Never checked" else "Checked ${formatWhen(lastCheckMillis)}",
        )
        UpdateStatus.Checking -> InfoRow("Status", "Checking…")
        is UpdateStatus.UpToDate -> {
            InfoRow("Status", "Up to date")
            InfoRow("Latest", "${status.release.versionName} (${status.release.versionCode})")
        }
        is UpdateStatus.Available -> {
            InfoRow("Status", "Update available")
            InfoRow("Latest", "${status.release.versionName} (${status.release.versionCode})")
            InfoRow("Size", formatSize(status.release.apkSizeBytes))
        }
        is UpdateStatus.Downloading -> InfoRow("Status", "Downloading ${status.release.versionName}…")
        is UpdateStatus.Installing -> InfoRow("Status", "Installing ${status.release.versionName}…")
        is UpdateStatus.Installed -> InfoRow("Status", "Installed ${status.release.versionName} — restarting")
        is UpdateStatus.Failed -> InfoRow("Status", status.message)
    }
}

private fun formatWhen(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatSize(bytes: Long): String = "%.1f MB".format(bytes / BYTES_PER_MB)

private const val BYTES_PER_MB = 1024.0 * 1024.0
