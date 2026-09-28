package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.CarSetting
import com.ripostelabs.carlauncher.carlib.CarSettingRisk
import com.ripostelabs.carlauncher.carlib.CarSettingsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Car settings: the Toyota customisations the stock head unit changed through the CAN box
 * ([com.ripostelabs.carlauncher.carlib.CarSettings]).
 *
 *     open ──▶ query ──▶ box ──▶ 0x62 report ──▶ rows
 *     tap  ──▶ set   ──▶ box ──▶ car ... query ──▶ 0x62 report ──▶ rows
 *
 * A row shows only what the car reported: disabled until the first report, and a tap changes
 * nothing on screen until the next one. So a setting the car ignores visibly stays put. Lock
 * rows ask first and only while parked (ConfirmDialog's destructive lock).
 */
@Composable
fun CarCustomizeScreen(
    carService: CarService,
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val state by carEvents.carSettings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<CarSetting, Int>?>(null) }

    // The stock page asks on every open; the box answers with one 0x62 frame.
    LaunchedEffect(Unit) { carService.requestCarSettings() }

    // One set frame, then a fresh report so the row shows what the car now holds.
    fun send(setting: CarSetting, value: Int) {
        carService.setCarSetting(setting, value)
        scope.launch {
            delay(REPORT_AFTER_SET_MS)
            carService.requestCarSettings()
        }
    }

    fun change(setting: CarSetting, value: Int) {
        if (setting.risk == CarSettingRisk.LOCKS) {
            pending = setting to value
            return
        }
        send(setting, value)
    }

    SettingsScaffold(title = "Car settings", onBack = onBack, subtitle = "Customisations the car keeps") {
        SettingsSection(title = "Feedback") {
            FEEDBACK_ROWS.forEach { CarSettingItem(it, state, ::change) }
        }
        SettingsSection(title = "Lights") {
            LIGHT_ROWS.forEach { CarSettingItem(it, state, ::change) }
        }
        SettingsSection(title = "Door locks") {
            LOCK_ROWS.forEach { CarSettingItem(it, state, ::change) }
        }
        Text(
            text = "Settings are sent to the car through the CAN box. Items the car ignores " +
                "stay unchanged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val (setting, value) = pending ?: return
    val row = LOCK_ROWS.first { it.setting == setting }
    ConfirmDialog(
        title = "Change ${row.label.lowercase()}?",
        message = "Set to \"${row.options.first { it.first == value }.second}\". Keep the keys " +
            "outside the car until you have checked how the doors behave.",
        confirmLabel = "Send",
        destructive = true,
        onConfirm = {
            pending = null
            send(setting, value)
        },
        onDismiss = { pending = null },
    )
}

/** How a row edits its value: a switch for on/off, a picker otherwise. */
private enum class RowKind { TOGGLE, PICKER }

private data class CarSettingRow(
    val setting: CarSetting,
    val label: String,
    val kind: RowKind,
    val options: List<Pair<Int, String>> = ON_OFF,
    val description: String? = null,
)

@Composable
private fun CarSettingItem(
    row: CarSettingRow,
    state: CarSettingsState?,
    onChange: (CarSetting, Int) -> Unit,
) {
    val value = state?.get(row.setting)

    // Never a guessed value: no report yet, or a field the report did not carry.
    if (value == null) {
        val why = if (state == null) "Waiting for the car" else "Not in the car's report"
        SettingRow(label = row.label, description = why, enabled = false) { ValueBadge("—") }
        return
    }

    when (row.kind) {
        RowKind.TOGGLE -> ToggleSetting(
            label = row.label,
            checked = value == ON,
            onChange = { onChange(row.setting, if (it) ON else OFF) },
            description = row.description,
        )
        RowKind.PICKER -> PickerSetting(
            label = row.label,
            current = value,
            options = row.options,
            onSelect = { onChange(row.setting, it) },
            description = row.description,
        )
    }
}

private const val OFF = 0
private const val ON = 1
private val ON_OFF = listOf(OFF to "Off", ON to "On")

/** Long enough for the car to apply the change before the box reads it back. */
private const val REPORT_AFTER_SET_MS = 1_000L

private const val BUZZER_MAX = 7

private val FEEDBACK_ROWS = listOf(
    CarSettingRow(
        CarSetting.BUZZER_VOLUME, "Lock buzzer volume", RowKind.PICKER,
        options = listOf(OFF to "Off") + (1..BUZZER_MAX).map { it to "$it" },
    ),
    CarSettingRow(CarSetting.LOCK_FLASH, "Flash hazards on lock and unlock", RowKind.TOGGLE),
)

private val LIGHT_ROWS = listOf(
    CarSettingRow(CarSetting.DAYTIME_LIGHTS, "Daytime running lights", RowKind.TOGGLE),
    CarSettingRow(
        CarSetting.INTERIOR_LIGHT_OFF, "Interior lights off after", RowKind.PICKER,
        options = listOf(0 to "Off", 1 to "7.5 s", 2 to "15 s", 3 to "30 s"),
    ),
    // The box labels are generic Toyota; the RAV4 steps are off, 30, 60, 90 s, index unconfirmed.
    CarSettingRow(
        CarSetting.HEADLIGHT_OFF, "Headlights off after doors close", RowKind.PICKER,
        options = listOf(0 to "Off", 1 to "Step 1 (30 s)", 2 to "Step 2 (60 s)", 3 to "Step 3 (90 s)"),
        description = "Step timing not yet confirmed on this car",
    ),
    CarSettingRow(
        CarSetting.LIGHT_SENSOR, "Auto headlight sensitivity", RowKind.PICKER,
        options = (0..4).map { it to "${it + 1}" },
        description = "Which end is brighter is not yet confirmed",
    ),
)

private val LOCK_ROWS = listOf(
    CarSettingRow(CarSetting.AUTO_LOCK_SPEED, "Lock when driving off", RowKind.TOGGLE),
    CarSettingRow(CarSetting.AUTO_LOCK_OUT_OF_P, "Lock when shifting out of P", RowKind.TOGGLE),
    CarSettingRow(CarSetting.AUTO_UNLOCK_INTO_P, "Unlock when shifting into P", RowKind.TOGGLE),
    CarSettingRow(CarSetting.AUTO_UNLOCK_DRIVER_DOOR, "Unlock when driver's door opens", RowKind.TOGGLE),
    CarSettingRow(
        CarSetting.SMART_DOOR_UNLOCK, "Smart entry unlocks", RowKind.PICKER,
        options = listOf(1 to "Driver's door", 0 to "All doors"),
    ),
    CarSettingRow(
        CarSetting.REMOTE_UNLOCK_TWO_PRESS, "Remote unlock", RowKind.PICKER,
        options = listOf(1 to "Driver first, all second", 0 to "All doors at once"),
    ),
)
