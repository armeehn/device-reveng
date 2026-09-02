package com.ripostelabs.carlauncher.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.ui.AutoSizeText
import com.ripostelabs.carlauncher.ui.theme.JetBrainsMono
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.CrashLog // v0.4.3.7
import com.ripostelabs.carlauncher.data.CrashRecord // v0.4.3.7
import com.ripostelabs.carlauncher.data.DoctorCheck
import com.ripostelabs.carlauncher.data.LauncherSettings
import com.ripostelabs.carlauncher.data.OemApps
import com.ripostelabs.carlauncher.data.SettingKeys
import com.ripostelabs.carlauncher.data.SettingsStore
import com.ripostelabs.carlauncher.data.SetupDoctor
import com.ripostelabs.carlauncher.data.SrcModeNames
import com.ripostelabs.carlauncher.ui.ParkedOnly // v0.4.3.7
import com.ripostelabs.carlauncher.ui.collectAsStateSafe
import com.ripostelabs.carlauncher.ui.theme.carShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.4.2 — Setup Doctor screen. Shows every grant the launcher needs, repairs the failing ones
 * in-app when root is present, and otherwise prints the exact adb command per row. Exists because a
 * reinstall silently drops these grants and the resulting "nothing works" is indistinguishable from
 * a bug without a screen that names what's missing.
 *
 * v0.4.3.7 — also the reader for [CrashLog]. Same reason it lives here: this is the screen someone
 * opens when the launcher is misbehaving, and "it died at 07:12 with this trace" belongs next to
 * "the notification listener is not enabled".
 */
@Composable
fun SetupDoctorScreen(
    controller: CarSettingsController,
    onBack: () -> Unit,
    settingsStore: SettingsStore? = null, // OEM-shadow policy (null keeps previews working)
    carService: CarService? = null, // live source mode; null = "gateway not bound"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doctor = remember { SetupDoctor(context.applicationContext, scope) }
    val checks by doctor.checks.collectAsStateSafe(initial = emptyList())
    val repairing by doctor.repairing.collectAsStateSafe(initial = false)
    val root by controller.rootAvailable.collectAsStateSafe(initial = null)
    val vendorApps by doctor.vendorApps.collectAsStateSafe(initial = null)

    // The vendor report depends on the driver's shadow toggles; re-probe when they change.
    val settings by (settingsStore?.settings?.collectAsStateSafe(initial = LauncherSettings())
        ?: remember { mutableStateOf(LauncherSettings()) })
    val shadowPolicy = remember(settings) { OemApps.ShadowPolicy.from(settings) }
    LaunchedEffect(shadowPolicy) { doctor.refresh(shadowPolicy) }

    var crashes by remember { mutableStateOf<List<CrashRecord>>(emptyList()) }
    var opened by remember { mutableStateOf<CrashRecord?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun reloadCrashes() {
        scope.launch { crashes = withContext(Dispatchers.IO) { CrashLog.read(context) } }
    }
    LaunchedEffect(Unit) { reloadCrashes() }

    // Back closes the open trace first; SettingsHost's handler only sees it once nothing is open.
    BackHandler(enabled = opened != null) { opened = null }

    val current = opened
    if (current != null) {
        // Reading a stack trace is a parked-only activity (LAUNCHER_DESIGN §1.4) — the same gate
        // SettingsHost puts in front of the SysVar browser, and for the same reason.
        ParkedOnly(feature = "Crash details", onBack = { opened = null }) {
            CrashDetail(record = current, onBack = { opened = null })
        }
        return
    }

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

        VendorAppsSection(report = vendorApps)
        GatewayStateSection(controller = controller, carService = carService)

        SettingsSection(title = "Crashes") {
            if (crashes.isEmpty()) {
                Text(
                    text = "No crashes recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            crashes.forEach { record ->
                CrashRow(record = record, onOpen = { opened = record })
            }
            if (crashes.isNotEmpty()) {
                ActionRow(
                    label = exported ?: "Export crash log",
                    description = "Copy the log to external storage so it can be pulled off the unit",
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                CrashLog.export(context, System.currentTimeMillis())
                            }
                            exported = file?.name ?: "Export failed"
                        }
                    },
                )
                // v0.4.7 — behind ConfirmDialog like reboot: a one-tap permanent delete of all
                // crash evidence was a mis-tap away while driving.
                ActionRow(
                    label = "Clear crash log",
                    description = "Delete every stored crash",
                    destructive = true,
                    onClick = { confirmClear = true },
                )
                Text(
                    text = "Pull over adb:\nadb pull /sdcard/Android/data/${context.packageName}/files/crash-logs/",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear crash log?",
            message = "Every stored crash record is deleted. This cannot be undone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                confirmClear = false
                scope.launch {
                    withContext(Dispatchers.IO) { CrashLog.clear(context) }
                    exported = null
                    reloadCrashes()
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/**
 * The OEM replacement matrix ([OemApps]) against this unit: what the drawer hides, what is
 * still visible and why, what may be uninstalled (as a line to copy, never a button), and which
 * KEEP package is missing. That last one is the alarm: no btsuite, canbus2, eventcenter or
 * zlink means the unit lost part of its own plumbing.
 */
@Composable
private fun VendorAppsSection(report: OemApps.Report?) {
    SettingsSection(title = "Vendor apps") {
        if (report == null) {
            MutedText("Checking…")
            return@SettingsSection
        }

        report.missingKeep.forEach { app ->
            WarningRow(
                title = "${app.packageName} missing or disabled",
                detail = "Never remove this one: ${app.why}",
            )
        }

        val hidden = report.shadowed.joinToString { it.label }
        MutedText(
            if (hidden.isEmpty()) "Nothing hidden from the drawer." else "Hidden from the drawer: $hidden.",
        )

        // Pending = REPLACED but still visible; say what each one waits for.
        report.pending.forEach { app ->
            InfoRow(label = app.label, value = OemApps.pendingReason(app, report.installed))
        }

        if (report.removable.isNotEmpty()) {
            MutedText(
                "Safe to remove. Nothing runs from here: copy each line into adb shell or a " +
                    "root shell yourself. -k keeps the app's data for a reinstall.",
            )
            report.removable.forEach { app ->
                MutedText("${app.label}: ${app.why}")
                CommandBox(OemApps.uninstallLine(app.packageName))
            }
        }

        val kept = OemApps.APPS.filter { it.oemClass == OemApps.OemClass.KEEP }
        MutedText("Never hidden: " + kept.joinToString { "${it.label} (${it.why.trimEnd('.')})" })
    }
}

/**
 * Live gateway state, read-only. Every value is a plain row: the driver reports them, the
 * launcher writes none of them. Source mode is polled off the main thread like Home does
 * (a blocking AIDL read in a composition body once spun a main-thread IPC loop).
 */
@Composable
private fun GatewayStateSection(controller: CarSettingsController, carService: CarService?) {
    val mode by produceState<Int?>(initialValue = null, carService) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                runCatching { carService?.getValidMode() }.getOrNull()
            }
            delay(SOURCE_MODE_POLL_MS)
        }
    }
    val sysVars by controller.snapshot.collectAsStateWithLifecycle()

    SettingsSection(title = "Gateway state") {
        InfoRow(label = "Source mode (getValidMode)", value = SrcModeNames.label(mode))
        InfoRow(
            label = "Sys_SoundManager_Type",
            value = sysVarValue(sysVars, SettingKeys.SOUND_MANAGER_TYPE) +
                if (sysVars[SettingKeys.SOUND_MANAGER_TYPE] == KILL3RD_OFF) " (kill3rdAPK off)" else "",
        )
        InfoRow(label = "Sys_UINumber", value = sysVarValue(sysVars, SettingKeys.UI_NUMBER_KEY))
        InfoRow(label = "Sys_Landscape", value = sysVarValue(sysVars, SettingKeys.LANDSCAPE))
        InfoRow(label = "Sys_CustomerType", value = sysVarValue(sysVars, SettingKeys.CUSTOMER_TYPE))
        InfoRow(label = "Sys_CarType", value = sysVarValue(sysVars, SettingKeys.CAR_TYPE))
        InfoRow(label = "Sys_Vehicle_deries", value = sysVarValue(sysVars, SettingKeys.VEHICLE_SERIES))
    }
}

private fun sysVarValue(sysVars: Map<String, String>, key: String): String =
    sysVars[key]?.takeIf { it.isNotBlank() } ?: "not set"

/** `Sys_SoundManager_Type` "1" (the firmware default) means sendMode never force-stops apps. */
private const val KILL3RD_OFF = "1"
private const val SOURCE_MODE_POLL_MS = 5_000L

@Composable
private fun MutedText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Same shape as a failing [DoctorCheckRow], without a command: there is nothing to run. */
@Composable
private fun WarningRow(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = "Needs attention",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            AutoSizeText(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AutoSizeText(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/** A monospaced line that copies itself on tap: the adb-command affordance, shared. */
@Composable
private fun CommandBox(command: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(carShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { clipboard.setText(AnnotatedString(command)) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = command,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
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

/** One stored crash: when it happened over the exception line, tappable for the whole trace. */
@Composable
private fun CrashRow(record: CrashRecord, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
    ) {
        AutoSizeText(
            text = crashTimeLabel(record),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AutoSizeText(
            text = record.summary.ifEmpty { "(no trace recorded)" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The full trace, monospaced and copyable — same copy affordance as the adb command rows above. */
@Composable
private fun CrashDetail(record: CrashRecord, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    SettingsScaffold(title = "Crash", onBack = onBack, subtitle = crashTimeLabel(record)) {
        SettingsSection {
            InfoRow(label = "Thread", value = record.thread.ifEmpty { "unknown" })
            InfoRow(label = "App version", value = record.version.ifEmpty { "unknown" })
        }
        SettingsSection(title = "Stack trace") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(carShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { clipboard.setText(AnnotatedString(record.trace)) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = record.trace.ifEmpty { "(no trace recorded)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy trace",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** A record written before the clock was set has no usable timestamp; show the thread instead. */
private fun crashTimeLabel(record: CrashRecord): String =
    if (record.timeMillis <= 0L) {
        "Unknown time"
    } else {
        SimpleDateFormat("MMM d, yyyy — HH:mm:ss", Locale.getDefault()).format(Date(record.timeMillis))
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
                AutoSizeText(
                    text = check.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AutoSizeText(
                    text = check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
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
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
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
