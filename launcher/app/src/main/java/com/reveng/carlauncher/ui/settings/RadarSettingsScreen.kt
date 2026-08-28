package com.reveng.carlauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.RadarState
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.SettingKeys

/**
 * v1.4 — Parking radar. Mirrors the vendor radar settings, reskinned, plus a live sensor
 * readout fed by [CarEvents.radar] (the unprotected `MCU_CAR_CAN_RADAR_INFO` frame). Settings
 * are SysVar-backed (CAR_API §2.3). The live bars only appear once a real frame arrives, so a
 * guessed byte layout never fabricates readings.
 */
@Composable
fun RadarSettingsScreen(
    controller: CarSettingsController,
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    snap
    val radar by carEvents.radar.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Parking radar", onBack = onBack) {
        SettingsSection(title = "Sensors") {
            ToggleSetting(
                label = "Radar enabled",
                description = "Show ultrasonic parking sensors",
                checked = controller.getBoolean(SettingKeys.RADAR_TYPE_ENABLE, true),
                onChange = { controller.setBoolean(SettingKeys.RADAR_TYPE_ENABLE, it) },
            )
        }

        SettingsSection(title = "Warning tone") {
            ToggleSetting(
                label = "Beep on approach",
                checked = controller.getBoolean(SettingKeys.RADAR_TONE_ENABLE, true),
                onChange = { controller.setBoolean(SettingKeys.RADAR_TONE_ENABLE, it) },
            )
            PickerSetting(
                label = "Tone type",
                current = controller.getInt(SettingKeys.RADAR_TONE_TYPE, 0),
                options = listOf(
                    0 to "Standard",
                    1 to "Soft",
                    2 to "Sharp",
                ),
                onSelect = { controller.setInt(SettingKeys.RADAR_TONE_TYPE, it) },
            )
        }

        SettingsSection(title = "Live sensors") {
            val rs = radar
            if (rs == null || !rs.valid) {
                Text(
                    text = "No radar data. The car only broadcasts sensor distances while " +
                        "reversing or at low speed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (rs.front.isNotEmpty()) SensorRow("Front", rs.front, rs)
                if (rs.front.isNotEmpty() && rs.rear.isNotEmpty()) Spacer(Modifier.height(12.dp))
                if (rs.rear.isNotEmpty()) SensorRow("Rear", rs.rear, rs)
            }
        }
    }
}

@Composable
private fun SensorRow(label: String, levels: List<Int>, rs: RadarState) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val clear = MaterialTheme.colorScheme.surfaceVariant
            val near = MaterialTheme.colorScheme.error
            levels.forEach { level ->
                val p = rs.proximity(level)
                val color = lerp(clear, near, p)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    if (level > 0) {
                        Text(
                            text = "$level",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}
