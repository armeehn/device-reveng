package com.reveng.carlauncher.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.RadarState
import com.reveng.carlauncher.nav.NavRepository
import com.reveng.carlauncher.nav.NavState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NavCard — CENTER/top home tile (LAUNCHER_DESIGN §2, 3-column).
 *
 * Two states:
 *  • idle  → "Tap to navigate" placeholder; tapping opens Google Maps / Android Auto.
 *  • live  → turn-by-turn instruction + distance + ETA, read from Maps' navigation
 *            notification via [NavRepository]/[com.reveng.carlauncher.nav.NavListenerService].
 *
 * Also carries the optional [DrivingInfo] speed readout (omitted when the CAN speed is
 * unavailable — CAR_API §1.3 note: there is no clean speed extra, so [CarEvents.speedKmh]
 * stays -1 unless separately wired) and embeds [RadarView] parking sensors when a frame
 * is present. Tapping anywhere launches Maps.
 */
@Composable
fun NavCard(
    carEvents: CarEvents,
    radar: RadarState?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val nav by NavRepository.state.collectAsStateSafe(initial = null)
    val speed by carEvents.speedKmh.collectAsStateSafe(initial = -1)

    // Best-effort: root-enable our notification listener so Maps nav is readable (no-op if
    // already enabled or if the unit isn't rooted). Same mechanism as the media listener.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { NavRepository.ensureListenerEnabled(context) }
    }

    Card(
        modifier = modifier.clickable { NavRepository.launchMaps(context) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Navigation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Navigation",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                // v0.7 DrivingInfo: shown only when a real speed value is available.
                DrivingInfo(speedKmh = speed)
            }

            if (nav != null) {
                NavLive(nav = nav!!)
            } else {
                NavIdle()
            }

            // Parking sensors (front/rear) — only renders when a frame is present.
            RadarView(
                state = radar,
                showPlaceholder = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NavLive(nav: NavState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.TurnRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = nav.instruction,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (nav.distance.isNotEmpty()) {
                Text(
                    text = nav.distance,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (nav.eta.isNotEmpty()) {
        Text(
            text = nav.eta,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NavIdle() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "Tap to navigate",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Small speed readout. Renders nothing when [speedKmh] < 0 (unavailable — never fabricated). */
@Composable
private fun DrivingInfo(speedKmh: Int) {
    if (speedKmh < 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Speed,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$speedKmh km/h",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
