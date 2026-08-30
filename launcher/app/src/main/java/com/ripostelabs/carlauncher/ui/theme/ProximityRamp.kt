package com.ripostelabs.carlauncher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The clear → near → close colour ramp the parking aids draw with (RadarSideStrip
 * arcs, ReverseOverlay guide bands). Extracted to a pure function so a table test
 * can assert the three steps stay pairwise distinct in every built-in theme.
 *
 * The middle step is DERIVED (primary blended halfway to error), not the tertiary
 * role: tertiary falls back to primary in every theme that leaves accent3 unset,
 * which silently collapsed clear and near into one colour in 10 of 11 presets.
 */
data class ProximityRamp(
    val clear: Color,
    val near: Color,
    val close: Color,
)

/** Halfway between clear and close — far enough from both to read as its own step. */
private const val NEAR_BLEND = 0.5f

fun proximityRamp(scheme: ColorScheme): ProximityRamp = ProximityRamp(
    clear = scheme.primary,
    near = lerp(scheme.primary, scheme.error, NEAR_BLEND),
    close = scheme.error,
)
