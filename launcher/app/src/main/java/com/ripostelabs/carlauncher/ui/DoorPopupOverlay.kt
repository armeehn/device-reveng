package com.ripostelabs.carlauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.DoorPopup
import com.ripostelabs.carlauncher.carlib.DoorState
import kotlinx.coroutines.delay

/**
 * RAV4-166: the door and hatch overlay, stock `DoorInfoWindow` redrawn. A top-down car outline
 * with each open door, the bonnet and the tailgate drawn in the error colour. [DoorPopup] says
 * when it shows; it hides after [DoorPopup.AUTO_HIDE_MS], on a tap, or when all shut.
 *
 *          bonnet
 *       ┌──────────┐
 *   FL  │          │  FR
 *   RL  │          │  RR
 *       └──────────┘
 *         tailgate
 */
@Composable
fun DoorPopupOverlay(carEvents: CarEvents) {
    val doors by carEvents.doors.collectAsStateWithLifecycle()
    val popup = remember { DoorPopup() }
    var shown by remember { mutableStateOf<DoorState?>(null) }

    // One decision per report: show and restart the timer, or hide.
    LaunchedEffect(doors) {
        val d = doors ?: return@LaunchedEffect
        when (popup.onDoors(d)) {
            DoorPopup.Action.SHOW -> shown = d
            DoorPopup.Action.HIDE -> shown = null
            DoorPopup.Action.NONE -> Unit
        }
    }

    val d = shown ?: return
    LaunchedEffect(d) {
        delay(DoorPopup.AUTO_HIDE_MS)
        shown = null
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.clickable { shown = null },
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CarOutline(
                    state = d,
                    body = MaterialTheme.colorScheme.onSurfaceVariant,
                    open = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = openLabel(d),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CarOutline(state: DoorState, body: Color, open: Color) {
    Canvas(modifier = Modifier.size(width = 120.dp, height = 200.dp)) {
        val inset = size.width * BODY_INSET
        val bodySize = Size(size.width - 2 * inset, size.height)
        val stroke = Stroke(width = STROKE_DP.dp.toPx())

        // Body outline, then one bar per opening on top of it.
        drawRoundRect(body, Offset(inset, 0f), bodySize, CornerRadius(inset), style = stroke)

        val half = size.height / 2
        val side = stroke.width * OPEN_WIDTH
        openBar(state.frontLeft, open, Offset(0f, size.height * DOOR_TOP), Size(side, half * DOOR_SPAN))
        openBar(state.rearLeft, open, Offset(0f, half), Size(side, half * DOOR_SPAN))
        openBar(state.frontRight, open, Offset(size.width - side, size.height * DOOR_TOP), Size(side, half * DOOR_SPAN))
        openBar(state.rearRight, open, Offset(size.width - side, half), Size(side, half * DOOR_SPAN))
        openBar(state.bonnet, open, Offset(inset, 0f), Size(bodySize.width, side))
        openBar(state.tailgate, open, Offset(inset, size.height - side), Size(bodySize.width, side))
    }
}

private fun DrawScope.openBar(isOpen: Boolean, color: Color, at: Offset, barSize: Size) {
    if (!isOpen) {
        return
    }
    drawRect(color, at, barSize)
}

/** "Driver's door open", "Tailgate and bonnet open": names in stock's order. */
private fun openLabel(s: DoorState): String {
    val names = buildList {
        if (s.frontLeft) { add("Driver's door") }
        if (s.frontRight) { add("Passenger door") }
        if (s.rearLeft) { add("Rear left door") }
        if (s.rearRight) { add("Rear right door") }
        if (s.tailgate) { add("Tailgate") }
        if (s.bonnet) { add("Bonnet") }
    }
    return names.joinToString(", ") + " open"
}

private const val BODY_INSET = 0.18f   // share of the width left for the door bars
private const val STROKE_DP = 4
private const val OPEN_WIDTH = 2.5f    // an open bar is this many strokes wide
private const val DOOR_TOP = 0.2f      // front doors start below the bonnet
private const val DOOR_SPAN = 0.6f     // each door covers this share of its half
