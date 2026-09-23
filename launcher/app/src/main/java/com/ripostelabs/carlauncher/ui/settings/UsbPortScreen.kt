package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.AndroidOwnerGate
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.carlib.UsbRole
import com.ripostelabs.carlauncher.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which way the GT6's one USB controller faces ([UsbRole]). The choice persists and the owner
 * path in MainActivity applies it at start and on change; this screen only records it.
 */
@Composable
fun UsbPortScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val stored by settingsStore.usbRole.collectAsStateWithLifecycle()
    var bench by remember { mutableStateOf<String?>(null) }
    var portNow by remember { mutableStateOf<UsbRole?>(null) }

    // Both need a shell: the image flag (plain getprop) and the node (root). Off the UI thread.
    LaunchedEffect(stored) {
        withContext(Dispatchers.IO) {
            bench = AndroidOwnerGate(context).benchProp()
            portNow = if (RootShell.isRootAvailable()) UsbRole.read { RootShell.exec(it) } else UsbRole.UNKNOWN
        }
    }

    val chosen = UsbRole.resolve(stored, bench)

    SettingsScaffold(
        title = "USB port",
        subtitle = "One controller: the car's sockets or the adb pigtail, never both",
        onBack = onBack,
    ) {
        SettingsSection(title = "Port") {
            PickerSetting(
                label = "USB port",
                current = chosen,
                options = OPTIONS,
                onSelect = { role -> settingsStore.setUsbRole(role) },
                description = HOST_WARNING,
            )
            InfoRow(label = "Port now", value = portNow?.let(::label) ?: "reading…")
            InfoRow(label = "Image default", value = bench?.let { label(UsbRole.default(it)) } ?: "reading…")
        }
    }
}

/** The two roles as the driver sees them; UNKNOWN is never offered. */
private val OPTIONS: List<Pair<UsbRole, String>> = listOf(
    UsbRole.HOST to "Car devices (host)",
    UsbRole.PERIPHERAL to "Computer, adb (peripheral)",
)

private const val HOST_WARNING =
    "Host: CANable, USB media and wired CarPlay work; adb over the pigtail stops until " +
        "switched back or the unit reboots into a bench image. Peripheral: adb on the pigtail, " +
        "the car's sockets are dead."

private fun label(role: UsbRole): String = OPTIONS.firstOrNull { it.first == role }?.second ?: "unknown"
