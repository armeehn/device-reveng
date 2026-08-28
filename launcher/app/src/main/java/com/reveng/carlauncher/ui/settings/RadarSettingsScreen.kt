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
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf // v2.8
import androidx.compose.runtime.remember // v2.8
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
import com.reveng.carlauncher.data.LauncherSettings // v2.8
import com.reveng.carlauncher.data.SettingKeys
import com.reveng.carlauncher.data.SettingsStore // v2.8
import com.reveng.carlauncher.ui.collectAsStateSafe // v2.8

/**
 * v1.4 — Parking radar. Mirrors the vendor radar settings, reskinned, plus a live sensor
 * readout fed by [CarEvents.radar] (the unprotected `MCU_CAR_CAN_RADAR_INFO` frame). Settings
 * are SysVar-backed (CAR_API §2.3). The live bars only appear once a real frame arrives, so a
 * guessed byte layout never fabricates readings.
 *
 * v2.8: the guess is now stated in words rather than left in a source comment, and
 * [RadarCaptureScreen] — the instrument that settles it — hangs off this screen.
 */
@Composable
fun RadarSettingsScreen(
    controller: CarSettingsController,
    carEvents: CarEvents,
    onBack: () -> Unit,
    settingsStore: SettingsStore? = null, // v2.8 (null keeps previews working)
    onOpenCapture: () -> Unit = {}, // v2.8
) {
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    snap
    val radar by carEvents.radar.collectAsStateWithLifecycle()
    // v2.8: whether a human has checked our guessed byte layout against this actual car.
    val settings by (settingsStore?.settings?.collectAsStateSafe(initial = LauncherSettings())
        ?: remember { mutableStateOf(LauncherSettings()) })
    val confirmed = settings.radarLayoutConfirmed

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

        // v2.8: say plainly that the decode below is a guess, and offer the tool that settles it.
        SettingsSection(title = "Byte layout") {
            Text(
                text = if (confirmed) {
                    "Marked confirmed on this car. Maneuvering side-strips are enabled."
                } else {
                    "UNCONFIRMED. The sensor order and the level polarity below are best-effort " +
                        "guesses — the vendor frame layout was never recovered. Run the capture " +
                        "to settle them; until then the maneuvering side-strips stay hidden."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (confirmed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.height(12.dp))
            ActionRow(
                label = "Raw frame capture",
                description = "Live per-byte hex with change tracking, next to our decode.",
                onClick = onOpenCapture,
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
                        .clip(carShape(8.dp))
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
