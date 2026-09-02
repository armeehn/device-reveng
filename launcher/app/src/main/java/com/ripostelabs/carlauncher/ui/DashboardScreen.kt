package com.ripostelabs.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.GpsSpeedSource
import com.ripostelabs.carlauncher.carlib.RadarState
import com.ripostelabs.carlauncher.data.IgnitionSession
import com.ripostelabs.carlauncher.ui.theme.carShape
import com.ripostelabs.carlauncher.ui.theme.carCard
import kotlinx.coroutines.delay

/**
 * v3.0 — the cockpit dashboard: everything the car tells us, on one glanceable surface.
 *
 * The launcher had accumulated vehicle signals in five places (status bar, nav card, radar
 * overlay, settings screens) and nowhere to see them together. This is that place.
 *
 * Every tile states its own provenance, because these signals are not equally trustworthy and
 * a dashboard that renders a guess identically to a confirmed reading is worse than no
 * dashboard. Speed is GPS-derived (v2.5); outside temp arrives preformatted from the car;
 * steering angle and radar are decoded from layouts that are still guesses, and say so.
 */
@Composable
fun DashboardScreen(
    carEvents: CarEvents,
    onBack: () -> Unit,
    // v0.4.7.1: the session start lives at activity scope (null keeps previews working) —
    // held inside this composition, it reset every time the Dashboard was opened.
    ignitionSession: IgnitionSession? = null,
) {
    val speed by carEvents.speedKmh.collectAsStateSafe(initial = GpsSpeedSource.SPEED_UNKNOWN)
    val motion by carEvents.motion.collectAsStateSafe(initial = CarEvents.Motion.UNKNOWN)
    val outsideTemp by carEvents.outsideTemp.collectAsStateSafe(initial = null)
    val steering by carEvents.steeringAngle.collectAsStateSafe(initial = CarEvents.VALUE_UNKNOWN)
    val accOn by carEvents.accOn.collectAsStateSafe(initial = true)
    val radar by carEvents.radar.collectAsStateSafe(initial = null)
    val sessionStartMs by (ignitionSession?.startedAt?.collectAsStateSafe(initial = null)
        ?: remember { androidx.compose.runtime.mutableStateOf<Long?>(null) })

    Column(modifier = Modifier.fillMaxSize()) {
        DashboardHeader(onBack = onBack)

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SpeedTile(
                speed = speed,
                motion = motion,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ValueTile(
                    label = "Outside",
                    value = outsideTemp ?: "—",
                    note = if (outsideTemp == null) "no reading yet" else "as reported by the car",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                TripTile(
                    sessionStartMs = sessionStartMs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                ValueTile(
                    label = "Ignition",
                    value = if (accOn) "ACC on" else "ACC off",
                    note = "vendor ACC broadcast",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SteeringTile(
                    angle = steering,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                RadarTile(
                    radar = radar,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DashboardHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .size(48.dp)
                .clip(carShape(12.dp))
                .clickable(onClick = withTapFeedback(onBack))
                .padding(8.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = "Vehicle",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** The one number worth reading at a glance, so it gets its own column. */
@Composable
private fun SpeedTile(speed: Int, motion: CarEvents.Motion, modifier: Modifier = Modifier) {
    val note = when (motion) {
        CarEvents.Motion.MOVING -> "moving · GPS"
        CarEvents.Motion.PARKED -> "parked · GPS"
        CarEvents.Motion.UNKNOWN -> "no GPS fix"
    }
    Tile(modifier = modifier) {
        AutoSizeText(
            text = if (speed < 0) "—" else "$speed",
            fontSize = SPEED_SP.sp,
            lineHeight = (SPEED_SP * 1.05f).sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "km/h",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TileNote(note)
    }
}

/**
 * Trip timer — the one value here the car does not provide.
 *
 * `CAN_CAR_TIRP_INFO` exists in the constant table, but no extras were recovered for it, so a
 * real trip computer is not readable. This times how long the ignition has been on, which is
 * honest about being ours: the label says "this session", not "trip".
 *
 * v0.4.7.1: the start lives in [IgnitionSession]; this tile only ticks and formats. Holding
 * the start here made every visit to the Dashboard restart the "session".
 */
@Composable
private fun TripTile(sessionStartMs: Long?, modifier: Modifier = Modifier) {
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(sessionStartMs) {
        if (sessionStartMs == null) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedMs = System.currentTimeMillis() - sessionStartMs
            delay(TRIP_TICK_MS)
        }
    }

    Tile(modifier = modifier) {
        AutoSizeText(
            text = formatElapsed(elapsedMs),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TileNote("this session · measured here, not by the car")
    }
}

/**
 * Steering angle, drawn relative to centre.
 *
 * No number is shown, deliberately. The extra's units and sign are undocumented (see
 * [CarEvents.steeringAngle]), so printing "42°" would invent a precision we do not have. A bar
 * that leans the way the wheel leans is exactly as much as the signal supports.
 */
@Composable
private fun SteeringTile(angle: Int, modifier: Modifier = Modifier) {
    val known = angle != CarEvents.VALUE_UNKNOWN
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    Tile(modifier = modifier) {
        Text(
            text = "Steering",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(STEERING_BAR_DP.dp),
        ) {
            val midY = size.height / 2f
            val midX = size.width / 2f
            drawLine(
                color = track,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = STEERING_TRACK_PX,
            )
            if (!known) {
                return@Canvas
            }
            // Clamp to the widest plausible raw excursion; the true range is unknown, so this
            // saturates rather than mis-scaling if the car reports a larger count.
            val ratio = (angle.toFloat() / STEERING_ASSUMED_RANGE).coerceIn(-1f, 1f)
            val x = midX + ratio * midX
            drawLine(
                color = accent,
                start = Offset(midX, midY),
                end = Offset(x, midY),
                strokeWidth = STEERING_TRACK_PX * 2f,
            )
            drawCircle(color = accent, radius = STEERING_KNOB_PX, center = Offset(x, midY))
        }
        Spacer(Modifier.height(8.dp))
        TileNote(
            if (known) "raw $angle · units unconfirmed" else "no reading yet",
        )
    }
}

/**
 * Radar, reported as what arrived rather than as a safety claim.
 *
 * v2.8 gates the maneuvering side-strip behind a user confirmation of the byte layout. This tile
 * is not gated because it does not tell the driver a distance — it says how many sensors are
 * reporting and how close the nearest reads on the *guessed* decode, with the guess labelled.
 */
@Composable
private fun RadarTile(radar: RadarState?, modifier: Modifier = Modifier) {
    Tile(modifier = modifier) {
        Text(
            text = "Parking radar",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        AutoSizeText(
            text = when {
                radar == null -> "—"
                radar.hasObstacle() -> "obstacle"
                else -> "clear"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        TileNote(
            if (radar == null) "no frame yet" else "decoded from a GUESSED byte layout",
        )
    }
}

@Composable
private fun ValueTile(
    label: String,
    value: String,
    note: String,
    modifier: Modifier = Modifier,
) {
    Tile(modifier = modifier) {
        AutoSizeText(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        AutoSizeText(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        TileNote(note)
    }
}

@Composable
private fun Tile(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .carCard()
            .clip(carShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

/** The provenance line every tile carries. Small on purpose — present, not shouting. */
@Composable
private fun TileNote(text: String) {
    AutoSizeText(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
        maxLines = 2,
    )
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private const val SPEED_SP = 96f
private const val STEERING_BAR_DP = 48
private const val STEERING_TRACK_PX = 6f
private const val STEERING_KNOB_PX = 14f

/**
 * ⚠ GUESSED. Assumes the raw extra spans roughly ±540 (three turns lock to lock in degrees, the
 * most common convention). Only affects how far the indicator travels, never a displayed number,
 * and saturates rather than overflowing if the real range is wider. Confirm lock to lock.
 */
private const val STEERING_ASSUMED_RANGE = 540f

/** A second is plenty for a session timer, and keeps the surface static between ticks. */
private const val TRIP_TICK_MS = 1_000L
