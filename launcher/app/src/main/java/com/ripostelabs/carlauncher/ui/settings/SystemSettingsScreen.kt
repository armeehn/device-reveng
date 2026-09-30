package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.BuildConfig
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.McuDiagnostics
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.0 — System & About. The read-mostly bottom of the vendor settings tree, reskinned:
 * firmware versions, car/customer profile, panel geometry and bus link speeds (read-only),
 * and the power actions (reboot / factory reset) behind a confirm dialog.
 *
 * Versions come first from the owner's own frames ([McuDiagnostics]: the SRC_MCU_VERSION ack and
 * the box's `0xF0`), which is the only source on Riposte OS 0.2; then the AIDL
 * (getMCUVer/getCanVer) on a stock image; then the SysVar mirror.
 */
@Composable
fun SystemSettingsScreen(
    controller: CarSettingsController,
    carService: CarService,
    onBack: () -> Unit,
    diagnostics: McuDiagnostics? = null,
) {
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    snap
    val connected by carService.connected.collectAsStateWithLifecycle()

    // Read the firmware versions once (per connection state) off the main thread. Calling these
    // blocking AIDL getters directly in the composition body ran main-thread IPC on every
    // recomposition and stalled composition if the vendor service hung.
    val mcuVerLive by produceState<String?>(null, connected) {
        value = if (connected) withContext(Dispatchers.IO) { carService.getMcuVersion() } else null
    }
    val canVerLive by produceState<String?>(null, connected) {
        value = if (connected) withContext(Dispatchers.IO) { carService.getCanVersion() } else null
    }
    // RAV4-193: the owner's frames first, see the class note.
    val owned by (diagnostics?.snapshot ?: EMPTY_DIAGNOSTICS).collectAsStateWithLifecycle()
    val mcuVer = SoftwareInfo.firstKnown(
        owned.mcuVersion, mcuVerLive, controller.getString(SettingKeys.MCU_VERSION, ""),
    )
    val canVer = SoftwareInfo.firstKnown(
        owned.canVersion, canVerLive, controller.getString(SettingKeys.CANBOX_VERSION, ""),
    )

    // RAV4-193: the rest of stock's software version page, read once off the main thread.
    val context = LocalContext.current
    val ram by produceState(SoftwareInfo.UNKNOWN) {
        value = withContext(Dispatchers.IO) { SoftwareInfo.ram(context) }
    }
    val storage by produceState(SoftwareInfo.UNKNOWN) {
        value = withContext(Dispatchers.IO) { SoftwareInfo.storage() }
    }

    var confirmReset by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Both power actions are blocking AIDL calls, and factoryReset() can sit in the gateway for
    // an arbitrarily long time before returning — on the main thread that is an ANR in HOME.
    fun power(action: () -> Unit) {
        scope.launch(Dispatchers.IO) { runCatching(action) }
    }

    SettingsScaffold(title = "System & about", onBack = onBack) {
        SettingsSection(title = "Firmware") {
            InfoRow("MCU version", mcuVer)
            InfoRow("CANBOX version", canVer)
        }

        SettingsSection(title = "Software") {
            InfoRow("Model", SoftwareInfo.model())
            InfoRow("Android", SoftwareInfo.android())
            InfoRow("Build", SoftwareInfo.build())
            InfoRow("Launcher", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("RAM", ram)
            InfoRow("Storage", storage)
            InfoRow("Serial", SoftwareInfo.serial())
        }

        SettingsSection(title = "Vehicle profile") {
            InfoRow("Car type", controller.getString(SettingKeys.CAR_TYPE, "—").ifBlank { "—" })
            InfoRow("Vehicle series", controller.getString(SettingKeys.VEHICLE_SERIES, "—").ifBlank { "—" })
            InfoRow("Customer/OEM id (default 88)", controller.getString(SettingKeys.CUSTOMER_TYPE, "—").ifBlank { "—" })
            InfoRow("UI skin (Sys_UINumber, 0 = common)", controller.getString(SettingKeys.UI_NUMBER_KEY, "—").ifBlank { "—" })
        }

        SettingsSection(title = "Regional") {
            PickerSetting(
                label = "Time format",
                current = controller.getInt(SettingKeys.TIME_FORMAT, 0),
                options = listOf(0 to "24-hour", 1 to "12-hour"),
                onSelect = { controller.setInt(SettingKeys.TIME_FORMAT, it) },
            )
            InfoRow("App version", controller.getString(SettingKeys.APP_VERSION, "—").ifBlank { "—" })
            InfoRow("System version", controller.getString(SettingKeys.SYSTEM_VERSION, "—").ifBlank { "—" })
        }

        SettingsSection(title = "Display panel") {
            InfoRow(
                "Resolution",
                "${controller.getString(SettingKeys.SCREEN_WIDTH, "?")} × " +
                    controller.getString(SettingKeys.SCREEN_HEIGHT, "?"),
            )
            InfoRow("Density", controller.getString(SettingKeys.SCREEN_DENSITY, "—").ifBlank { "—" })
        }

        SettingsSection(title = "Bus") {
            // v0.4.7 — refuse-listed link speeds (ProtectedSettingKeys): a wrong write silences
            // reverse, SWC and climate. Read-only raw value — the old pickers wrote invented enum
            // mappings and fabricated "125k" when the key was absent.
            InfoRow("CAN baud rate", controller.getString(SettingKeys.CAN_BAUD_RATE, "—").ifBlank { "—" })
            InfoRow("MCU UART baud", controller.getString(SettingKeys.MCU_COM_BAUDRATE, "—").ifBlank { "—" })
        }

        SettingsSection(title = "Power") {
            ActionRow(
                label = "Reboot head unit",
                onClick = { confirmReboot = true },
                enabled = connected,
            )
            ActionRow(
                label = "Factory reset",
                description = "Return the head unit to its first boot",
                onClick = { confirmReset = true },
                destructive = true,
                enabled = connected,
            )
        }
    }

    if (confirmReboot) {
        ConfirmDialog(
            title = "Reboot head unit?",
            message = "The unit will restart now.",
            confirmLabel = "Reboot",
            // v0.4.3.8: destructive, so ConfirmDialog's parked-only lock withholds the confirm
            // while moving. A mis-tap at speed takes reverse camera, SWC, radio and launcher down.
            destructive = true,
            onConfirm = { confirmReboot = false; power { carService.reboot() } },
            onDismiss = { confirmReboot = false },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Factory reset?",
            message = "The head unit returns to its first boot. This cannot be undone.",
            confirmLabel = "Continue",
            destructive = true,
            onConfirm = { confirmReset = false; confirmWipe = true },
            onDismiss = { confirmReset = false },
        )
    }
    // Second confirm: Android's own wipe, so say plainly what goes.
    if (confirmWipe) {
        ConfirmDialog(
            title = "Erase everything?",
            message = WIPE_MESSAGE,
            confirmLabel = "Erase",
            destructive = true,
            onConfirm = { confirmWipe = false; power { carService.factoryReset() } },
            onDismiss = { confirmWipe = false },
        )
    }
}

/** Stands in for the diagnostics when the owner has not started, so the rows fall through. */
private val EMPTY_DIAGNOSTICS = kotlinx.coroutines.flow.MutableStateFlow(McuDiagnostics.Snapshot())

private const val WIPE_MESSAGE = "Erases all apps, accounts and settings on the head unit."
