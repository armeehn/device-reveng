package com.reveng.carlauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.0 — System & About. The read-mostly bottom of the vendor settings tree, reskinned:
 * firmware versions, car/customer profile, panel geometry (read-only), a couple of writable
 * bus baud rates, and the power actions (reboot / factory reset) behind a confirm dialog.
 *
 * Versions come live from the AIDL where available (getMCUVer/getCanVer); the rest is SysVar.
 */
@Composable
fun SystemSettingsScreen(
    controller: CarSettingsController,
    carService: CarService,
    onBack: () -> Unit,
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
    val mcuVer = mcuVerLive ?: controller.getString(SettingKeys.MCU_VERSION, "—").ifBlank { "—" }
    val canVer = canVerLive ?: controller.getString(SettingKeys.CANBOX_VERSION, "—").ifBlank { "—" }

    var confirmReset by remember { mutableStateOf(false) }
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

        SettingsSection(title = "Vehicle profile") {
            InfoRow("Car type", controller.getString(SettingKeys.CAR_TYPE, "—").ifBlank { "—" })
            InfoRow("Vehicle series", controller.getString(SettingKeys.VEHICLE_SERIES, "—").ifBlank { "—" })
            InfoRow("Customer/OEM UI", controller.getString(SettingKeys.CUSTOMER_TYPE, "—").ifBlank { "—" })
            InfoRow("UI skin key", controller.getString(SettingKeys.UI_NUMBER_KEY, "default").ifBlank { "default" })
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
            PickerSetting(
                label = "CAN baud rate",
                current = controller.getInt(SettingKeys.CAN_BAUD_RATE, 0),
                options = listOf(0 to "125k", 1 to "250k", 2 to "500k", 3 to "1M"),
                onSelect = { controller.setInt(SettingKeys.CAN_BAUD_RATE, it) },
            )
            PickerSetting(
                label = "MCU UART baud",
                current = controller.getInt(SettingKeys.MCU_COM_BAUDRATE, 0),
                options = listOf(0 to "9600", 1 to "19200", 2 to "38400", 3 to "115200"),
                onSelect = { controller.setInt(SettingKeys.MCU_COM_BAUDRATE, it) },
            )
        }

        SettingsSection(title = "Power") {
            ActionRow(
                label = "Reboot head unit",
                onClick = { confirmReboot = true },
                enabled = connected,
            )
            ActionRow(
                label = "Factory reset",
                description = "Restore vendor defaults — erases your settings",
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
            onConfirm = { confirmReboot = false; power { carService.reboot() } },
            onDismiss = { confirmReboot = false },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Factory reset?",
            message = "This restores the vendor firmware defaults and erases your custom " +
                "settings. This cannot be undone.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = { confirmReset = false; power { carService.factoryReset() } },
            onDismiss = { confirmReset = false },
        )
    }
}
