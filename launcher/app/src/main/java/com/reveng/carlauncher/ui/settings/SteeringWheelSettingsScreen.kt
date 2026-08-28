package com.reveng.carlauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.SettingKeys

/**
 * v1.8 — Steering wheel. A reskinned view of the vendor SWC-learn page:
 *
 *  * **Live key monitor** — every [CarEvents.swcKeys] press/release, with its raw index and ADC
 *    voltage, so the user can identify which physical button sends what (the same signal the
 *    vendor's learn page shows).
 *  * **Current mapping** — the launcher's built-in CAR_KEY_* → action table ([SwcNavigator]).
 *  * **Learn mode** — writes the vendor learn SysVar so the MCU associates the next press
 *    (best-effort; the full learn handshake is MCU-side, noted inline).
 *
 * Protected `STEER_WHEEL_INFOR` only reaches us as a privileged/system app; as a normal app the
 * monitor stays quiet (CAR_API §4, §7).
 */
@Composable
fun SteeringWheelSettingsScreen(
    controller: CarSettingsController,
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val log = remember { mutableStateListOf<CarEvents.SwcKey>() }
    var learnMode by remember { mutableStateOf(false) }

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
                            text = "Key #${k.keyIndex}",
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

        SettingsSection(title = "Learn a key") {
            ToggleSetting(
                label = "Learn mode",
                description = "Arm the MCU to associate the next wheel press",
                checked = learnMode,
                onChange = {
                    learnMode = it
                    controller.setInt(SettingKeys.WHEEL_KEY_LEARN_CUSTOM, if (it) 1 else 0)
                },
            )
            ActionRow(
                label = "Save learned mapping",
                description = "Commit the current wheel-key learn to the MCU",
                onClick = { controller.setInt(SettingKeys.WHEEL_CUSTOM_KEY_SAVE, 1) },
            )
            Text(
                text = "Full key learning is an MCU handshake; these controls arm/commit the " +
                    "vendor learn state. Verify on-device.",
                style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun StateDot(down: Boolean) {
    val color = if (down) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(6.dp))
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
