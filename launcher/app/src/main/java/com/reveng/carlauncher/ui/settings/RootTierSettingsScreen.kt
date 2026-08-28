package com.reveng.carlauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.VendorLauncher
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.RootTierController
import kotlinx.coroutines.delay

/**
 * v2.9 — Root tier: the capabilities Magisk buys us now that the vendor platform key is confirmed
 * unobtainable and the "install as a platform-signed system app" tier is off the table for good.
 *
 * The screen leads with what is *actually* working rather than with the switches. Every control
 * here depends on root, two of them write keys whose meaning is GUESSED, and one can wedge the
 * unit's vendor UI — so "is capture live", "do these keys exist on this unit" and "how do I get
 * back" are the facts a user needs before they touch anything, not after.
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
    val chromeKeys by rootTier.chromeKeysPresent.collectAsStateWithLifecycle()
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

        SettingsSection(title = "Vendor status & nav bar") {
            ToggleSetting(
                label = "Hide vendor bars",
                description = chromeDescription(chromeKeys),
                checked = chromeHidden,
                enabled = rooted && chromeKeys?.isNotEmpty() == true,
                onChange = { rootTier.setChromeHidden(it) },
            )
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

/** Both keynames are GUESSED, so say which ones this unit actually has rather than implying both. */
private fun chromeDescription(present: List<String>?): String = when {
    present == null -> "Checking which vendor keys this unit has…"
    present.isEmpty() -> "Neither vendor key exists on this unit — nothing to hide"
    else -> "Writes ${present.joinToString(", ")}; restores the original value when off"
}

/** The one instruction that gets a user out of trouble. Repeated verbatim in launcher/README.md. */
private const val RECOVERY_LINE =
    "adb shell pm enable com.szchoiceway.customerui"

private const val MILLIS_PER_SECOND = 1_000L
