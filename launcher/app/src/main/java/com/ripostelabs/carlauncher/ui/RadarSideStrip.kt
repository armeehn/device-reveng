package com.ripostelabs.carlauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.carlib.RadarState
import com.ripostelabs.carlauncher.ui.theme.proximityRamp

/**
 * v2.8 — the low-speed maneuvering side-strips (LAUNCHER_DESIGN §3.6).
 *
 * **Why the edges, and why not during reverse.** [ReverseOverlay]'s v0.9 rewrite established the
 * rule this obeys: when reverse engages the vendor composites its own full-screen reverse window
 * *above* our activity, as a separate system window. We cannot draw over that feed, and fighting
 * it — an opaque cover, a focus grab, a modal — either blanks the panel or interferes with the
 * vendor view's own handling. So this strip is not a reverse feature at all. It covers the case
 * the vendor window does *not*: creeping forward in a car park, a tight forward turn, easing out
 * of a garage, where the front sensors are live and no camera is on screen.
 *
 * The caller ([com.ripostelabs.carlauncher.MainActivity]) therefore hides it whenever reverse is
 * engaged. Two overlays never contend for the same pixels, and neither has to know about the
 * other's state beyond that one flag.
 *
 * Rails, not a picture of the car. Two 44 dp columns hard against the screen edges leave the whole
 * middle of the panel to whatever screen is underneath — this is peripheral information, read
 * without looking away from the windscreen, exactly like the pillarbox furniture the same
 * constraint produced on other work.
 *
 * Static by construction. A proximity display is the obvious place to reach for a pulse, and the
 * motion budget in launcher/README.md forbids it: the moment the strip most needs to be read is
 * the moment an animation is most distracting. Colour and fill carry the signal.
 *
 * Gated on the decode being trusted — see `LauncherSettings.radarLayoutConfirmed`. The left→right
 * sensor order [RadarState] splits corners on is UNVERIFIED, and an arc that says "clear" on the
 * side the obstacle is actually on is worse than no arc at all.
 */
@Composable
fun RadarSideStrip(
    state: RadarState?,
    modifier: Modifier = Modifier,
) {
    if (state == null || !state.valid) {
        return
    }

    // Green → amber → red derived from the theme rather than literal colours, so the strip
    // re-skins with the rest of the launcher; same ramp as ParkingGuideLines. See proximityRamp
    // for why the middle step is derived instead of reading the tertiary role.
    val ramp = proximityRamp(MaterialTheme.colorScheme)
    val clear = ramp.clear
    val near = ramp.near
    val close = ramp.close
    val track = MaterialTheme.colorScheme.surfaceVariant

    Box(modifier = modifier.fillMaxSize()) {
        SideRail(
            front = state.edgeProximity(RadarState.Edge.LEFT, RadarState.Bank.FRONT),
            rear = state.edgeProximity(RadarState.Edge.LEFT, RadarState.Bank.REAR),
            mirrored = false,
            clear = clear,
            near = near,
            close = close,
            track = track,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        SideRail(
            front = state.edgeProximity(RadarState.Edge.RIGHT, RadarState.Bank.FRONT),
            rear = state.edgeProximity(RadarState.Edge.RIGHT, RadarState.Bank.REAR),
            mirrored = true,
            clear = clear,
            near = near,
            close = close,
            track = track,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/**
 * One edge rail: the front group arcs from the top, the rear group from the bottom, both opening
 * away from the screen edge so they read as radiating out from that corner of the car.
 *
 * [mirrored] flips the arcs for the right-hand rail. This is screen geometry, not the reachability
 * mirror — the strips show which side of the *car* is close and must never swap with the columns.
 */
@Composable
private fun SideRail(
    front: Float,
    rear: Float,
    mirrored: Boolean,
    clear: Color,
    near: Color,
    close: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .width(RAIL_WIDTH_DP.dp)
            .fillMaxHeight(),
    ) {
        drawArcGroup(front, atTop = true, mirrored, clear, near, close, track)
        drawArcGroup(rear, atTop = false, mirrored, clear, near, close, track)
    }
}

/**
 * Three nested arcs for one sensor group. The innermost lights first, so the count of lit arcs is
 * readable at a glance and the colour confirms it — the two channels agree instead of the colour
 * carrying it alone (which fails for a red/green-deficient driver in a red-lit night cabin).
 */
private fun DrawScope.drawArcGroup(
    proximity: Float,
    atTop: Boolean,
    mirrored: Boolean,
    clear: Color,
    near: Color,
    close: Color,
    track: Color,
) {
    val stroke = Stroke(width = ARC_STROKE_PX)
    val lit = (proximity * ARC_COUNT).toInt().coerceIn(0, ARC_COUNT)
    val ramp = listOf(clear, near, close)

    // Centred on the screen edge, so only the half opening inward is on the panel.
    val cx = if (mirrored) size.width else 0f
    val cy = if (atTop) {
        size.height * GROUP_CENTER_FRACTION
    } else {
        size.height * (1f - GROUP_CENTER_FRACTION)
    }
    val start = if (mirrored) ARC_START_RIGHT_DEG else ARC_START_LEFT_DEG

    for (ring in 0 until ARC_COUNT) {
        val radius = ARC_INNER_PX + ring * ARC_GAP_PX
        val color = if (ring < lit) ramp[ring] else track

        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = ARC_SWEEP_DEG,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2, radius * 2),
            style = stroke,
        )
    }
}

/** Narrow enough to stay peripheral on a 1920px panel, wide enough for three legible arcs. */
private const val RAIL_WIDTH_DP = 44

private const val ARC_COUNT = 3
private const val ARC_INNER_PX = 34f
private const val ARC_GAP_PX = 22f
private const val ARC_STROKE_PX = 9f

/** Half a turn: the rail sits on the screen edge, so only the inward half is ever visible. */
private const val ARC_SWEEP_DEG = 180f

/** 0° is 3 o'clock and sweeps clockwise, so -90 → +90 is the right half, +90 → +270 the left. */
private const val ARC_START_LEFT_DEG = -90f
private const val ARC_START_RIGHT_DEG = 90f

/** Front group sits a quarter down the rail, rear a quarter up, leaving the middle clear. */
private const val GROUP_CENTER_FRACTION = 0.25f
