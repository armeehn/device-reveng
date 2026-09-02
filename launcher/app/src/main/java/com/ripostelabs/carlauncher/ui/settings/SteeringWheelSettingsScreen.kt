package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.ripostelabs.carlauncher.ui.theme.carShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.WheelFunction
import com.ripostelabs.carlauncher.carlib.WheelKeyMap
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.SettingKeys

/**
 * v1.8 — Steering wheel. A reskinned view of the vendor SWC-learn page:
 *
 *  * **Live key monitor** — every [CarEvents.swcKeys] press/release, with its slot, the
 *    learned function behind it and the ADC voltage, so the user can identify which physical
 *    button sends what (the same signal the vendor's learn page shows).
 *  * **Learned wheel keys** — the vendor learn app's slot → function map ([WheelKeyMap]).
 *  * **Current mapping** — the launcher's function → action table ([SwcNavigator]).
 *
 * Protected `STEER_WHEEL_INFOR` only reaches us as a privileged/system app; as a normal app the
 * monitor stays quiet (CAR_API §4, §7).
 *
 * v2.4.2: the old "Learn mode" toggle / "Save learned mapping" action were REMOVED. They wrote
 * scalars (`wheel_key_learn_custom`=0/1, `Set_Mcu_Wheel_Custom_Key_Save`=1) into vendor SysVar
 * keys, but the vendor gateway (`com.szchoiceway.eventcenter`) stores a **JSON object** under
 * `wheel_key_learn_custom` — `EventService.initSysEventState → onReadMcuWheelCustomKey` does
 * `Gson.fromJson(value, …)` on it at every startup. A scalar there makes that parse throw
 * ("Expected BEGIN_OBJECT but was NUMBER"), and because it runs during init the whole gateway
 * **crash-loops on boot** — taking the top-bar app-exit, SWC and HVAC down with it. The schema
 * is now known (`{"<icon id>":"<slot>"}`, [WheelKeyMap]) and parsed READ-ONLY here; learning
 * itself is an MCU handshake (`sendWheelKey` 112/slot/114/113, KDoc on [WheelKeyMap]) still
 * left to the vendor settings app. Nothing on this screen writes.
 */
@Composable
fun SteeringWheelSettingsScreen(
    controller: CarSettingsController,
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val log = remember { mutableStateListOf<CarEvents.SwcKey>() }
    val sysVars by controller.snapshot.collectAsStateWithLifecycle()
    val wheel = remember(sysVars[SettingKeys.WHEEL_KEY_LEARN_CUSTOM]) {
        WheelKeyMap.parse(sysVars[SettingKeys.WHEEL_KEY_LEARN_CUSTOM])
    }

    LaunchedEffect(Unit) {
        carEvents.swcKeys.collect { key ->
            log.add(0, key)
            while (log.size > 6) log.removeAt(log.lastIndex)
        }
    }

    SettingsScaffold(title = "Steering wheel", onBack = onBack) {
        SettingsSection(title = "Live key monitor") {
            if (log.isEmpty()) {
                Text(
                    text = "Press a wheel button to see it here. If nothing appears, the wheel " +
                        "events are protected and need a privileged install.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                log.forEach { k ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StateDot(k.down)
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = keyLabel(k, wheel),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = (if (k.down) "DOWN" else "UP") + "  ·  ${k.voltage} mV",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsSection(title = "Learned wheel keys") {
            if (wheel.isEmpty) {
                Text(
                    text = "No wheel keys learned (or the vendor map is unreadable). Until one " +
                        "is, wheel presses are read as fixed key codes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                wheel.entries.forEach { (slot, function) ->
                    MappingRow("Slot $slot", functionLabel(function))
                }
            }
        }

        SettingsSection(title = "Learn a key") {
            Text(
                text = "Steering-wheel key learning is an MCU handshake handled by the factory " +
                    "settings app, which stores the learned mapping in the vendor gateway's own " +
                    "format. This launcher only reads that map — an earlier build wrote " +
                    "the wrong data type and crash-looped the gateway on boot. Use the vendor " +
                    "settings app to learn a wheel key; it will appear in the monitor above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection(title = "Current mapping") {
            MappingRow("Previous / seek left", "Media previous")
            MappingRow("Next / seek right", "Media next")
            MappingRow("Media", "Play / pause")
            MappingRow("Home", "Go to Home")
            MappingRow("Back", "Back")
            MappingRow("Menu / Fav", "Select (center)")
            MappingRow("Left tune ◀ / ▶", "Navigate left / right")
            MappingRow("Right tune ◀ / ▶", "Navigate up / down")
        }
    }
}

/**
 * "Slot 1 · Next" for a learned resistive key, "Key #5" for a panel-fallback CAR_KEY code or
 * an unlearned slot. The slot is shown as the learn app numbers it (LPARAM − 1).
 */
private fun keyLabel(key: CarEvents.SwcKey, wheel: WheelKeyMap): String {
    if (key.space == CarEvents.KeySpace.CAR_KEY) {
        return "Key #${key.keyIndex}"
    }
    val slot = WheelKeyMap.slotOfLparam(key.keyIndex)
    val function = wheel.functionOf(slot) ?: return "Slot $slot"
    return "Slot $slot · ${functionLabel(function)}"
}

/** Driver-facing name for a learned function; the enum name is good enough for the rest. */
private fun functionLabel(function: WheelFunction): String = when (function) {
    WheelFunction.MODE -> "Source / mode"
    WheelFunction.NEXT -> "Next"
    WheelFunction.PREV -> "Previous"
    WheelFunction.POWER -> "Power"
    WheelFunction.NAVI -> "Navigation"
    WheelFunction.MUTE -> "Mute"
    WheelFunction.HANG_UP -> "Hang up"
    WheelFunction.TALK -> "Answer / talk"
    WheelFunction.VOLUME_UP -> "Volume up"
    WheelFunction.VOLUME_DOWN -> "Volume down"
    WheelFunction.VOICE -> "Voice"
    WheelFunction.HOME -> "Home"
    WheelFunction.BACK -> "Back"
    WheelFunction.OK -> "OK / select"
    else -> function.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun StateDot(down: Boolean) {
    val color = if (down) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(carShape(6.dp))
            .background(color),
    )
}

@Composable
private fun MappingRow(key: String, action: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = action,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}
