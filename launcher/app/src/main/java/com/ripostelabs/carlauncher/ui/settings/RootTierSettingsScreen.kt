package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.VendorChrome
import com.ripostelabs.carlauncher.carlib.VendorLauncher
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.RootTierController
import kotlinx.coroutines.delay

/**
 * v2.9 — Root tier: the capabilities Magisk buys us now that the vendor platform key is confirmed
 * unobtainable and the "install as a platform-signed system app" tier is off the table for good.
 *
 * The screen leads with what is *actually* working rather than with the switches. Every control
 * here depends on root or a bound gateway, and one can wedge the unit's vendor UI — so "is
 * capture live", "can this unit act on the key" and "how do I get back" are the facts a user
 * needs before they touch anything, not after.
 *
 * The nav-bar toggle writes `Sys_Customer_NaviBar_Height_Key` (see [VendorChrome]). The status
 * bar has no SysVar switch and is not offered.
 */
@Composable
fun RootTierSettingsScreen(
    controller: CarSettingsController,
    rootTier: RootTierController,
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val rootAvailable by controller.rootAvailable.collectAsStateWithLifecycle()
    val rootCapture by carEvents.rootCapture.collectAsStateWithLifecycle()
    val chromeHidden by rootTier.chromeHidden.collectAsStateWithLifecycle()
    val chromeSupport by rootTier.chromeSupport.collectAsStateWithLifecycle()
    val vendorState by rootTier.vendorLauncher.collectAsStateWithLifecycle()
    val rollbackDeadline by rootTier.rollbackDeadline.collectAsStateWithLifecycle()

    var confirmSoleHome by remember { mutableStateOf(false) }

    // Recompute the countdown once a second, and only while one is armed — a ticker that runs on
    // an idle screen is exactly the kind of background animation the v2.5 motion budget rules out.
    var secondsLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(rollbackDeadline) {
        val deadline = rollbackDeadline
        if (deadline == null) {
            secondsLeft = 0
            return@LaunchedEffect
        }
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            secondsLeft = ((remaining + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
            if (secondsLeft <= 0) {
                rootTier.refresh()
                return@LaunchedEffect
            }
            delay(MILLIS_PER_SECOND)
        }
    }

    val rooted = rootAvailable == true

    SettingsScaffold(
        title = "Root tier",
        onBack = onBack,
        subtitle = if (rooted) "Magisk root detected" else "Root not detected — these are inert",
    ) {
        SettingsSection(title = "Status") {
            InfoRow("Root shell", rootStatusLabel(rootAvailable))
            InfoRow("Protected broadcast capture", if (rootCapture) "Live" else "Not seen yet")
            InfoRow("Vendor launcher", vendorLauncherLabel(vendorState))
        }

        SettingsSection(title = "Vendor nav bar") {
            ToggleSetting(
                label = "Hide vendor nav bar",
                description = chromeDescription(chromeSupport),
                checked = chromeHidden,
                enabled = rooted && chromeSupport == VendorChrome.Support.READY,
                onChange = { rootTier.setChromeHidden(it) },
            )
            InfoRow("Status bar", "No vendor key hides it; the gateway always enables it")
        }

        SettingsSection(title = "Sole-HOME mode") {
            if (vendorState == VendorLauncher.State.DISABLED) {
                ActionRow(
                    label = "Restore vendor launcher",
                    description = "Re-enables ${VendorLauncher.PACKAGE}",
                    onClick = { rootTier.enableVendorLauncher() },
                    enabled = rooted,
                )
            } else {
                ActionRow(
                    label = "Disable vendor launcher",
                    description = "Reversible. Automatically undone in " +
                        "${VendorLauncher.ROLLBACK_WINDOW_SEC}s unless you confirm.",
                    onClick = { confirmSoleHome = true },
                    destructive = true,
                    enabled = rooted && vendorState == VendorLauncher.State.ENABLED,
                )
            }

            if (secondsLeft > 0) {
                ActionRow(
                    label = "Keep it disabled (${secondsLeft}s)",
                    description = "Otherwise the vendor launcher comes back on its own.",
                    onClick = { rootTier.keepVendorLauncherDisabled() },
                )
            }

            InfoRow("If the unit misbehaves", RECOVERY_LINE)
        }
    }

    // Destructive, so ConfirmDialog's v2.5 parked-only lock withholds the confirm while moving.
    if (confirmSoleHome) {
        ConfirmDialog(
            title = "Disable the vendor launcher?",
            message = "This disables ${VendorLauncher.PACKAGE}. It is not Android's HOME app, so " +
                "this launcher keeps working — but the vendor gateway draws its status bar and " +
                "side window out of that package, and how it copes with the package being gone " +
                "is untested.\n\n" +
                "A root shell will re-enable it automatically after " +
                "${VendorLauncher.ROLLBACK_WINDOW_SEC}s unless you confirm on the next screen. " +
                "That shell does not survive a reboot: $RECOVERY_LINE",
            confirmLabel = "Disable",
            destructive = true,
            onConfirm = {
                confirmSoleHome = false
                rootTier.disableVendorLauncher()
            },
            onDismiss = { confirmSoleHome = false },
        )
    }
}

private fun rootStatusLabel(available: Boolean?): String = when (available) {
    null -> "Probing…"
    true -> "Available"
    false -> "Not available"
}

private fun vendorLauncherLabel(state: VendorLauncher.State): String = when (state) {
    VendorLauncher.State.ENABLED -> "Enabled"
    VendorLauncher.State.DISABLED -> "Disabled"
    VendorLauncher.State.ABSENT -> "Not installed"
    VendorLauncher.State.UNKNOWN -> "Unknown"
}

/** Say why the toggle is inert rather than letting it look broken. */
private fun chromeDescription(support: VendorChrome.Support?): String = when (support) {
    null -> "Checking whether this unit honours the key…"
    VendorChrome.Support.KEY_ABSENT ->
        "${VendorChrome.KEY_NAVIBAR_HEIGHT} does not exist on this unit — nothing to hide"
    VendorChrome.Support.NOT_LANDSCAPE ->
        "The gateway only honours the key with ${VendorChrome.KEY_LANDSCAPE} = 1; this unit is not"
    VendorChrome.Support.READY ->
        "Sets ${VendorChrome.KEY_NAVIBAR_HEIGHT} to 0; restores the original height when off"
}

/** The one instruction that gets a user out of trouble. Repeated verbatim in launcher/README.md. */
private const val RECOVERY_LINE =
    "adb shell pm enable com.szchoiceway.customerui"

private const val MILLIS_PER_SECOND = 1_000L
