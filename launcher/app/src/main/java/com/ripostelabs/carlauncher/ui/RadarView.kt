package com.ripostelabs.carlauncher.ui

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
import com.ripostelabs.carlauncher.ui.theme.carShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.carlib.RadarState

/**
 * RadarView — front + rear parking-sensor zones from [RadarState] (CAR_API §1.3
 * MCU_CAR_CAN_RADAR_INFO). Each sensor is a bar tinted by proximity: cool/tertiary when
 * clear, ramping to error-red as an obstacle gets closer.
 *
 * Renders only when a real frame is present ([state] non-null & valid) — the frame typically
 * only arrives while reversing / at low speed. When there's no data it shows a compact
 * placeholder (or nothing, if [showPlaceholder] is false, e.g. embedded in the reverse
 * overlay). The sensor layout & polarity are GUESSED in [RadarState] — verify on-device.
 */
@Composable
fun RadarView(
    state: RadarState?,
    modifier: Modifier = Modifier,
    showPlaceholder: Boolean = true,
) {
    if (state == null || !state.valid) {
        if (showPlaceholder) {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = "Parking sensors idle",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.front.isNotEmpty()) {
            SensorRow(label = "Front", levels = state.front, proximity = state::proximity)
        }
        if (state.rear.isNotEmpty()) {
            SensorRow(label = "Rear", levels = state.rear, proximity = state::proximity)
        }
    }
}

@Composable
private fun SensorRow(
    label: String,
    levels: List<Int>,
    proximity: (Int) -> Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LABEL_COL_DP.dp),
        )
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            levels.forEach { level ->
                SensorBar(
                    proximity = proximity(level),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SensorBar(proximity: Float, modifier: Modifier = Modifier) {
    // Ramp: clear (tertiary) → near (error). Use theme colors so it re-skins with the theme.
    val clear = MaterialTheme.colorScheme.tertiary
    val near = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = if (proximity <= 0f) track else lerp(clear, near, proximity.coerceIn(0f, 1f))
    Box(
        modifier = modifier
            .height(20.dp)
            .clip(carShape(6.dp))
            .background(color),
    )
}

/** A one-line proximity caption for embedding under a card title. */
@Composable
fun RadarSummaryLine(state: RadarState?, modifier: Modifier = Modifier) {
    if (state == null || !state.valid || !state.hasObstacle()) return
    Text(
        text = "Obstacle detected",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** Wide enough for "Front"/"Rear" at the 16 sp label floor, mono themes included. */
private const val LABEL_COL_DP = 64
