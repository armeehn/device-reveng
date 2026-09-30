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
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.SettingKeys
import com.ripostelabs.carlauncher.data.SystemTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.0 — System & About. The read-mostly bottom of the vendor settings tree, reskinned:
 * firmware versions, car/customer profile, panel geometry and bus link speeds (read-only),
 * and the power actions (reboot / factory reset) behind a confirm dialog.
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
            TimeRows()
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

private const val WIPE_MESSAGE = "Erases all apps, accounts and settings on the head unit."

/**
 * RAV4-175: automatic time and zone, the zone, daylight saving and the language. Each switch
 * shows what the system holds after the root write, so a refused write leaves it unchanged.
 */
@Composable
private fun TimeRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoTime by remember { mutableStateOf(SystemTime.isAuto(context, SystemTime.Auto.TIME)) }
    var autoZone by remember { mutableStateOf(SystemTime.isAuto(context, SystemTime.Auto.ZONE)) }
    var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }

    fun setAuto(auto: SystemTime.Auto, on: Boolean) {
        val switch = if (on) SystemTime.Switch.ON else SystemTime.Switch.OFF
        scope.launch {
            withContext(Dispatchers.IO) { SystemTime.setAuto(auto, switch) }
            autoTime = SystemTime.isAuto(context, SystemTime.Auto.TIME)
            autoZone = SystemTime.isAuto(context, SystemTime.Auto.ZONE)
        }
    }

    ToggleSetting(
        label = "Automatic time",
        checked = autoTime,
        onChange = { setAuto(SystemTime.Auto.TIME, it) },
        description = "From the network, or GPS when offline",
    )
    ToggleSetting(
        label = "Automatic time zone",
        checked = autoZone,
        onChange = { setAuto(SystemTime.Auto.ZONE, it) },
        description = "From the network",
    )
    PickerSetting(
        label = "Time zone",
        current = zone,
        // The unit's own zone may be one the list leaves out (UTC on a fresh image): show it anyway.
        options = (listOf(zone) + SystemTime.zones()).distinct().map { it to it.replace('_', ' ') },
        onSelect = { id ->
            scope.launch {
                withContext(Dispatchers.IO) { SystemTime.setZone(id) }
                zone = ZoneId.systemDefault().id
            }
        },
        description = if (autoZone) "Turn off automatic time zone to pick one" else null,
        enabled = !autoZone,
    )
    val dst = when (SystemTime.dst(ZoneId.of(zone), Instant.now())) {
        SystemTime.Dst.IN_EFFECT -> "In effect now, set by the zone"
        SystemTime.Dst.NOT_NOW -> "Not in effect now, set by the zone"
        SystemTime.Dst.NEVER -> "Not used in this zone"
    }
    InfoRow("Daylight saving", dst)
    ActionRow(
        label = "Language",
        description = Locale.getDefault().displayName,
        onClick = { SystemTime.openLanguages(context) },
    )
}
