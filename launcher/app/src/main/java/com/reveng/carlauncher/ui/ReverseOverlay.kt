package com.reveng.carlauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.carlib.RadarState
import com.reveng.carlauncher.ui.theme.proximityRamp

/**
 * Reverse-camera overlay (CAR_API §1.3, §6.3 "Reverse / radar").
 *
 * v0.9 COEXISTENCE REWRITE — rationale:
 *   The reverse camera on this unit is NOT a Camera2 device we can host. When reverse
 *   engages, the VENDOR composites its OWN full-screen reverse window (its BackCar view)
 *   ON TOP of everything, as a separate system window above our activity. We cannot draw
 *   over that feed and we must not fight it:
 *     • NO opaque cover — the old version painted a full-screen black Box. That was wrong:
 *       in any state where the vendor window is momentarily absent it would blank the head
 *       unit, and where the vendor window IS present our black draws under it uselessly.
 *     • NO focus grab — no dialogs, no `focusable()`, no modal scrim. Stealing window focus
 *       could interfere with the vendor view's own touch/close handling.
 *   Instead we render a fully-transparent, minimal, non-intrusive overlay that YIELDS the
 *   center to the vendor camera and only decorates the edges: optional static parking-guide
 *   lines and the live [RadarState] bars + a short guidance line at the bottom. Because the
 *   vendor window composites above us, these extras are only visible in the (assumed) margins
 *   the vendor view doesn't cover — which is exactly the non-intrusive behavior we want.
 *
 * The static guide lines are toggleable via a small corner chip (local state, default on).
 */
@Composable
fun ReverseOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    radar: RadarState? = null,               // v0.9
    guideLinesDefaultOn: Boolean = true,     // v0.9
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        // NOTE: intentionally NO .background(...) here — the overlay is transparent so the
        // vendor reverse window (above us) is never occluded. See KDoc coexistence rationale.
        var guideLines by remember { mutableStateOf(guideLinesDefaultOn) }

        Box(modifier = Modifier.fillMaxSize()) {
            // Optional static parking-guide lines (fixed trajectory, not steering-linked).
            if (guideLines) {
                ParkingGuideLines(modifier = Modifier.fillMaxSize())
            }

            // Small, non-intrusive toggle for the guide lines (top-end corner). The chip stays
            // visually small so it never competes with the camera feed, but this is a control on
            // the REVERSING screen, so the invisible Box around it carries the §1.2 76 dp target.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .defaultMinSize(minWidth = TOGGLE_TARGET_DP.dp, minHeight = TOGGLE_TARGET_DP.dp)
                    .clickable { guideLines = !guideLines },
                contentAlignment = Alignment.TopEnd,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    shape = carShape(50),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = if (guideLines) "Guide lines: On" else "Guide lines: Off",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // Bottom edge: radar bars + guidance. Only renders when a real frame is present
            // (RadarView shows nothing when state is null/invalid, showPlaceholder = false).
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadarView(
                    state = radar,
                    showPlaceholder = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (radar != null && radar.valid && radar.hasObstacle()) {
                    Text(
                        text = "Obstacle — check surroundings",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

/**
 * Static (fixed) parking-guide lines: two side rails converging slightly toward the top plus
 * three distance bands (red/yellow/green). These are GEOMETRIC guides only — not linked to
 * steering angle (that dynamic trajectory would come from ZXW_CAN_WHEEL_TRACK_EVT, which the
 * vendor view already renders). Drawn semi-transparent so they read over the camera feed.
 */
@Composable
private fun ParkingGuideLines(modifier: Modifier = Modifier) {
    // Same derived clear/near/close ramp as RadarSideStrip — the tertiary role collapses
    // into primary in themes that leave accent3 unset (see proximityRamp).
    val ramp = proximityRamp(MaterialTheme.colorScheme)
    val red = ramp.close.copy(alpha = BAND_ALPHA)
    val amber = ramp.near.copy(alpha = BAND_ALPHA)
    val green = ramp.clear.copy(alpha = BAND_ALPHA)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Guides occupy the lower ~55% of the screen (near field behind the car).
        val top = h * 0.45f
        val bottom = h * 0.98f
        // Rails: wider at the bottom (near bumper), converging toward the top.
        val bottomInset = w * 0.22f
        val topInset = w * 0.36f
        val stroke = (w * 0.006f).coerceAtLeast(3f)

        // Left + right rails.
        drawLine(green, Offset(bottomInset, bottom), Offset(topInset, top), strokeWidth = stroke)
        drawLine(green, Offset(w - bottomInset, bottom), Offset(w - topInset, top), strokeWidth = stroke)

        // Distance bands across the rails (near=red, mid=amber, far=green).
        fun bandAt(t: Float, color: Color) {
            val y = bottom + (top - bottom) * t
            val inset = bottomInset + (topInset - bottomInset) * t
            drawLine(color, Offset(inset, y), Offset(w - inset, y), strokeWidth = stroke)
        }
        bandAt(0.12f, red)
        bandAt(0.45f, amber)
        bandAt(0.85f, green)
    }
}

/** Driving-relevant touch target (LAUNCHER_DESIGN §1.2) for the guide-line toggle. */
private const val TOGGLE_TARGET_DP = 76

/** Guide lines are semi-transparent so they read over the camera feed. */
private const val BAND_ALPHA = 0.85f
