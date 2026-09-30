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
        SettingsSection(title = "Climate") {
            CLIMATE_ROWS.forEach { CarSettingItem(it, state, ::change) }
        }
        SettingsSection(title = "Parking sensors") {
            RADAR_ROWS.forEach { CarSettingItem(it, state, ::change) }
        }
        SettingsSection(title = "Driving") {
            DRIVING_ROWS.forEach { CarSettingItem(it, state, ::change) }
        }
        SettingsSection(title = "Cluster") {
            CLUSTER_ROWS.forEach { CarSettingItem(it, state, ::change) }
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
    // Units and language: the car never reports them, so the row sends but shows no value.
    if (!row.setting.reported) {
        PickerSetting(
            label = row.label,
            current = NO_VALUE,
            options = row.options,
            onSelect = { onChange(row.setting, it) },
            description = "The car does not report this one",
        )
        return
    }

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
            onSelect = { if (it != value) { onChange(row.setting, it) } },
            description = row.description,
        )
    }
}

private const val OFF = 0
private const val ON = 1
private const val NO_VALUE = -1
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
    // Stock labels 1/3 "driver door linkage unlocking" and 1/2 "auto door unlock".
    CarSettingRow(CarSetting.AUTO_UNLOCK_DRIVER_DOOR, "Driver's door unlocks all doors", RowKind.TOGGLE),
    CarSettingRow(
        CarSetting.SMART_DOOR_UNLOCK, "Auto unlock", RowKind.PICKER,
        options = listOf(1 to "Driver's door", 0 to "All doors"),
    ),
    CarSettingRow(CarSetting.SMART_LOCK, "Smart lock and push start", RowKind.TOGGLE),
    CarSettingRow(CarSetting.KEY_TWICE_UNLOCK, "Unlock when the key is used twice", RowKind.TOGGLE),
    CarSettingRow(
        CarSetting.REMOTE_UNLOCK_TWO_PRESS, "Remote unlock", RowKind.PICKER,
        options = listOf(1 to "Driver first, all second", 0 to "All doors at once"),
    ),
)

/** Stock shows 0..4 as -2..+2 and 0..6 as -3..+3. */
private fun centred(count: Int): List<Pair<Int, String>> = (0 until count).map { it to "%+d".format(it - count / 2) }

private const val SEAT_STEPS = 5
private const val SMOKE_STEPS = 7
private const val RADAR_VOLUME_MAX = 5

private val CLIMATE_ROWS = listOf(
    CarSettingRow(CarSetting.CLIMATE_AUTO_LINK, "A/C follows AUTO", RowKind.TOGGLE),
    CarSettingRow(CarSetting.RECIRC_AUTO_LINK, "Recirculation follows AUTO", RowKind.TOGGLE),
    CarSettingRow(CarSetting.LEFT_SEAT_AUTO_TEMP, "Driver seat auto climate", RowKind.PICKER, centred(SEAT_STEPS)),
    CarSettingRow(CarSetting.RIGHT_SEAT_AUTO_TEMP, "Passenger seat auto climate", RowKind.PICKER, centred(SEAT_STEPS)),
    CarSettingRow(CarSetting.SMOKE_SENSOR, "Exhaust sensor sensitivity", RowKind.PICKER, centred(SMOKE_STEPS)),
)

private val RADAR_ROWS = listOf(
    CarSettingRow(CarSetting.RADAR_DISPLAY, "Show parking sensors", RowKind.TOGGLE),
    CarSettingRow(
        CarSetting.RADAR_VOLUME, "Parking sensor volume", RowKind.PICKER,
        options = (1..RADAR_VOLUME_MAX).map { it to "$it" },
    ),
    CarSettingRow(
        CarSetting.FRONT_RADAR_RANGE, "Front sensor range", RowKind.PICKER,
        options = listOf(1 to "1 square", 2 to "2 squares"),
    ),
    CarSettingRow(
        CarSetting.REAR_RADAR_RANGE, "Rear sensor range", RowKind.PICKER,
        options = listOf(1 to "1 square", 2 to "2 squares"),
    ),
)

private val DRIVING_ROWS = listOf(
    CarSettingRow(CarSetting.ACC_CUSTOM, "Radar cruise customisation", RowKind.TOGGLE),
    CarSettingRow(
        CarSetting.RECOMMENDATIONS, "Vehicle recommendations", RowKind.PICKER,
        options = listOf(0 to "Off", 1 to "When stopped", 2 to "On"),
    ),
    CarSettingRow(
        CarSetting.STEERING_EXIT_MOVE, "Steering wheel moves on exit", RowKind.PICKER,
        options = listOf(0 to "Off", 1 to "Tilt", 2 to "Telescopic", 3 to "Both"),
        description = "Only for a powered steering column",
    ),
    CarSettingRow(
        CarSetting.SEAT_EXIT_MOVE, "Driver seat moves on exit", RowKind.PICKER,
        options = listOf(0 to "Off", 1 to "Partial", 2 to "Full"),
        description = "Only for a powered seat with memory",
    ),
    CarSettingRow(
        CarSetting.DRIVE_SIDE, "Steering side", RowKind.PICKER,
        options = listOf(0 to "Left-hand drive", 1 to "Right-hand drive"),
    ),
)

private val CLUSTER_ROWS = listOf(
    CarSettingRow(
        CarSetting.FUEL_UNIT, "Fuel economy unit", RowKind.PICKER,
        options = listOf(2 to "L/100 km", 1 to "km/L", 0 to "MPG (US)", 3 to "MPG (UK)"),
    ),
    CarSettingRow(
        CarSetting.TEMP_UNIT, "Temperature unit", RowKind.PICKER,
        options = listOf(0 to "°C", 1 to "°F"),
    ),
    // Stock's language codes (HiworldToyotaSetConfig.java:67).
    CarSettingRow(
        CarSetting.LANGUAGE, "Language", RowKind.PICKER,
        options = listOf(
            1 to "English", 5 to "French", 7 to "Spanish", 9 to "Portuguese", 2 to "Chinese",
            3 to "Chinese (Taiwan)", 15 to "Arabic", 19 to "Thai", 30 to "Vietnamese",
            42 to "Indonesian", 43 to "Malay",
        ),
    ),
)
