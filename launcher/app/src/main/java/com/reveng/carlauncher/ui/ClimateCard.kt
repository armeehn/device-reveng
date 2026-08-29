package com.reveng.carlauncher.ui

import com.reveng.carlauncher.ui.theme.carCard
import com.reveng.carlauncher.ui.theme.DISABLED_ALPHA
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.carlib.ClimateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * ClimateReadout — compact, **display-only** HVAC summary for the LEFT column
 * (CAR_API §3.5 / §5). No write path in the companion build (§6.4).
 *
 * Data source, in preference order:
 *  1. the `com.szchoiceway.canbus.carairstruct` broadcast surfaced by [CarEvents.climate]
 *     (when a decodable frame is delivered), then
 *  2. AIDL `getAirData()` via [CarService], polled on a slow timer.
 *
 * Both are best-effort: the vendor `CarAirState` Parcelable class isn't bundled and the
 * `getAirData()` byte layout is GUESSED ([ClimateState] KDoc). When neither yields a valid
 * frame the card shows a **"Climate unavailable"** placeholder rather than fabricating values.
 */
@Composable
fun ClimateReadout(
    carService: CarService,
    carEvents: CarEvents,
    modifier: Modifier = Modifier,
) {
    val broadcast by carEvents.climate.collectAsStateSafe(initial = null)

    val polled by produceState(initialValue = ClimateState(valid = false)) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                runCatching { ClimateState.fromAirData(carService.getAirData()) }
                    .getOrDefault(ClimateState(valid = false))
            }
            delay(5_000)
        }
    }

    val state: ClimateState? = broadcast?.takeIf { it.valid } ?: polled.takeIf { it.valid }

    Card(
        modifier = modifier.carCard(accent = MaterialTheme.colorScheme.tertiary), // marigold in rotation
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        if (state == null) {
            ClimateUnavailable()
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left set-temp — the primary glance value.
                Column {
                    Text(
                        text = state.leftTempLabel(),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.dualOn) {
                        Text(
                            text = "Dual ${state.rightTempLabel()}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Mode glyph row.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ClimateGlyph(Icons.Filled.AcUnit, "A/C", state.acOn)
                    ClimateGlyph(Icons.Filled.Autorenew, "AUTO", state.autoOn)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Air,
                            contentDescription = "Fan",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = state.fanLevel.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClimateGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
) {
    val tint = if (on) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(28.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun ClimateUnavailable() {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Thermostat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Climate unavailable",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
