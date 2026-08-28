package com.reveng.carlauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.SettingKeys

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

    val mcuVer = (if (connected) carService.getMcuVersion() else null)
        ?: controller.getString(SettingKeys.MCU_VERSION, "—").ifBlank { "—" }
    val canVer = (if (connected) carService.getCanVersion() else null)
        ?: controller.getString(SettingKeys.CANBOX_VERSION, "—").ifBlank { "—" }

    var confirmReset by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }

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
            onConfirm = { confirmReboot = false; carService.reboot() },
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
            onConfirm = { confirmReset = false; carService.factoryReset() },
            onDismiss = { confirmReset = false },
        )
    }
}
