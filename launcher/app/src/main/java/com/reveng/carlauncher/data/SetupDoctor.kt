package com.reveng.carlauncher.data

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.reveng.carlauncher.carlib.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One environment check the launcher needs to actually work, and how to fix it. [ok] is the live
 * state; [adbCommand] is the copy-paste fix over adb (always available); [rootCommand] is the same
 * fix runnable in-app via [RootShell] when root is present, or null when only adb can do it.
 */
data class DoctorCheck(
    val id: String,
    val title: String,
    val detail: String,
    val ok: Boolean,
    val adbCommand: String,
    val rootCommand: String?,
    /**
     * The Android runtime permission this check covers, when it has a normal request path (a system
     * permission dialog). Null for checks with no such path (the notification-listener grants).
     * First-run onboarding derives its permission-request set from this so the required grants are
     * defined once, here, rather than re-hardcoded there.
     */
    val runtimePermission: String? = null,
)

/**
 * v0.4.2 — the Setup Doctor.
 *
 * A fresh side-load silently loses the grants the launcher's features depend on: the location
 * permission the motion gate reads, the Bluetooth-status permission the chips read, and the three
 * notification-listener bindings (media / nav / shelf). Nothing errors — the features just quietly
 * do nothing, which reads as "the launcher is broken" rather than "a grant is missing" (see the
 * grants-after-reinstall hazard the project has hit repeatedly).
 *
 * This probes each grant and, when root is present, repairs it in-app; without root it prints the
 * exact adb command per row. Every check keys off [Context.getPackageName] at runtime, so it is
 * correct under the `.debug` applicationId too — the fixes target the app that is actually running.
 */
class SetupDoctor(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val pkg = appContext.packageName

    private val _checks = MutableStateFlow<List<DoctorCheck>>(emptyList())
    val checks: StateFlow<List<DoctorCheck>> = _checks.asStateFlow()

    private val _repairing = MutableStateFlow(false)
    val repairing: StateFlow<Boolean> = _repairing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            _checks.value = withContext(Dispatchers.IO) { probe() }
        }
    }

    /** Run every failing, root-fixable check's repair via one root session, then re-probe. */
    fun repairAll() {
        scope.launch {
            _repairing.value = true
            withContext(Dispatchers.IO) {
                val commands = probe()
                    .filter { !it.ok && it.rootCommand != null }
                    .map { it.rootCommand!! }
                if (commands.isNotEmpty() && runCatching { RootShell.isRootAvailable() }.getOrDefault(false)) {
                    // Each command is independent, and RootShell now runs them that way: one
                    // failing `pm grant` (already granted, or a vendor `pm` quirk) must not drop
                    // the listener grants queued behind it. RootShell refuses newline-bearing
                    // commands, and none here contain one (fixed package/component/permission
                    // strings).
                    runCatching { RootShell.exec(*commands.toTypedArray()) }
                        .onSuccess { res ->
                            if (!res.ok) Log.w(TAG, "repairAll: these did not apply: ${res.failures}")
                        }
                }
            }
            _checks.value = withContext(Dispatchers.IO) { probe() }
            _repairing.value = false
        }
    }

    private fun probe(): List<DoctorCheck> {
        val enabledListeners = enabledNotificationListeners()
        val checks = mutableListOf<DoctorCheck>()

        checks += permissionCheck(
            id = "location",
            title = "Location permission",
            detail = "Needed for the motion gate — parked-only features stay locked without it.",
            permission = android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        checks += permissionCheck(
            id = "bluetooth",
            title = "Bluetooth permission",
            detail = "Needed for the Bluetooth status chip.",
            permission = android.Manifest.permission.BLUETOOTH_CONNECT,
        )
        // WRITE_SETTINGS is special-access (an appop, not a runtime dialog), and a reinstall
        // drops it like the rest: without it the brightness slider silently does nothing.
        checks += DoctorCheck(
            id = "write_settings",
            title = "Modify system settings",
            detail = "Needed by the brightness slider (framework backlight writes).",
            ok = Settings.System.canWrite(appContext),
            adbCommand = "adb shell appops set $pkg WRITE_SETTINGS allow",
            rootCommand = "appops set $pkg WRITE_SETTINGS allow",
        )
        checks += listenerCheck(
            id = "listener_media",
            title = "Media notification listener",
            detail = "Needed to read active media sessions for the now-playing card.",
            component = "$pkg/com.reveng.carlauncher.media.MediaListenerService",
            enabled = enabledListeners,
        )
        checks += listenerCheck(
            id = "listener_nav",
            title = "Navigation notification listener",
            detail = "Needed to read Google Maps' turn-by-turn for the nav card.",
            component = "$pkg/com.reveng.carlauncher.nav.NavListenerService",
            enabled = enabledListeners,
        )
        checks += listenerCheck(
            id = "listener_shelf",
            title = "Notification shelf listener",
            detail = "Needed for the parked-only notification shelf.",
            component = "$pkg/com.reveng.carlauncher.notif.ShelfListenerService",
            enabled = enabledListeners,
        )
        checks += suiteCheck()
        return checks
    }

    /**
     * v0.5 — how much of the com.reveng.* suite is actually on the unit.
     *
     * Not a grant, but it fails the same silent way: the suite installs app by app, and a member
     * that failed to install just never appears in the drawer. Naming the missing packages here
     * is the difference between "the Clock rewrite doesn't work" and "the Clock rewrite isn't
     * installed". Never [ok] == false in a way that blocks anything — the launcher works fine
     * with none of them, so a partial suite is reported, not treated as a fault to repair.
     */
    private fun suiteCheck(): DoctorCheck {
        val installed = RevengSuite.installed(installedPackages())
        val missing = RevengSuite.missing(installedPackages())
        val detail = if (missing.isEmpty()) {
            "All ${RevengSuite.APPS.size} rewritten apps are installed."
        } else {
            "Missing: " + missing.joinToString { it.label }
        }
        return DoctorCheck(
            id = "reveng_suite",
            title = "Rewritten app suite (${installed.size}/${RevengSuite.APPS.size})",
            detail = detail,
            ok = missing.isEmpty(),
            // The suite ships as a set of ordinary APKs; there is no single command that
            // installs them, so this points at the directory they are published from.
            adbCommand = "adb install-multiple <rav4-apps>/apps/*/app-debug.apk",
            rootCommand = null,
        )
    }

    /** Installed package names, empty if PackageManager refuses the query. */
    private fun installedPackages(): Set<String> = runCatching {
        appContext.packageManager
            .getInstalledPackages(0)
            .mapTo(mutableSetOf()) { it.packageName }
    }.getOrElse {
        Log.w(TAG, "package enumeration failed", it)
        emptySet()
    }

    private fun permissionCheck(
        id: String,
        title: String,
        detail: String,
        permission: String,
    ): DoctorCheck {
        val granted = ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
        return DoctorCheck(
            id = id,
            title = title,
            detail = detail,
            ok = granted,
            adbCommand = "adb shell pm grant $pkg $permission",
            rootCommand = "pm grant $pkg $permission",
            runtimePermission = permission,
        )
    }

    private fun listenerCheck(
        id: String,
        title: String,
        detail: String,
        component: String,
        enabled: Set<String>,
    ): DoctorCheck {
        return DoctorCheck(
            id = id,
            title = title,
            detail = detail,
            ok = component in enabled,
            adbCommand = "adb shell cmd notification allow_listener $component",
            rootCommand = "cmd notification allow_listener $component",
        )
    }

    /** Flattened component names of every currently-enabled notification listener on the device. */
    private fun enabledNotificationListeners(): Set<String> {
        val raw = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return raw.split(':').filter { it.isNotBlank() }.toSet()
    }

    private companion object {
        const val TAG = "SetupDoctor"
    }
}
