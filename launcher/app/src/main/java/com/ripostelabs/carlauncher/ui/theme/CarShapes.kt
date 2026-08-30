package com.ripostelabs.carlauncher.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The active theme's [ThemeStyle], provided by [CarLauncherTheme]. Screens never read this
 * directly — they go through [carShape] / [carCard], which no-op for the default style.
 */
val LocalCarStyle = staticCompositionLocalOf { ThemeStyle() }

/**
 * Theme-aware replacement for a literal `RoundedCornerShape(radius)`. The requested radius
 * is multiplied by the active [ThemeStyle.cornerScale], so a sharp-cornered theme
 * (Riposte: scale 0) flattens every corner in the app without per-screen changes.
 */
@Composable
fun carShape(radius: Dp): RoundedCornerShape =
    RoundedCornerShape(radius * LocalCarStyle.current.cornerScale)

/** Percent-based overload for pill shapes (`RoundedCornerShape(50)`). */
@Composable
fun carShape(percent: Int): RoundedCornerShape =
    if (LocalCarStyle.current.cornerScale == 0f) RoundedCornerShape(0) else RoundedCornerShape(percent)

/**
 * The Material component [Shapes] (Card, Surface, Button defaults…) scaled the same way,
 * so M3 components that never specify a shape also honour the theme's corner style.
 */
fun carShapes(scale: Float): Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp * scale),
    small = RoundedCornerShape(8.dp * scale),
    medium = RoundedCornerShape(12.dp * scale),
    large = RoundedCornerShape(16.dp * scale),
    extraLarge = RoundedCornerShape(28.dp * scale),
)

// Riposte brand card chrome (ripostelabs.xyz/brand, SEC.01/SEC.04): drawn, not lit.
private val HARD_BORDER = 2.dp // "2px solid — structural borders"
private val HARD_OFFSET = 4.dp // "no blur — hard 4px 4px 0 offset only for depth"
private val ACCENT_BAR = 6.dp // "6px accent — card/step top bars"

/**
 * Card chrome for hard-edged themes: a 2dp structural border, a hard 4dp×4dp offset
 * shadow (no blur), and an optional 6dp accent top bar. Returns `this` unchanged unless
 * the active theme sets [ThemeStyle.hardEdge], so decorating a card is safe for every
 * theme. Apply *before* any `clip()` so the shadow isn't clipped away.
 */
@Composable
fun Modifier.carCard(accent: Color? = null): Modifier {
    val style = LocalCarStyle.current
    if (!style.hardEdge) return this
    val ink = MaterialTheme.colorScheme.onBackground
    // On a light (bone) field the shadow is ink; on a dark field a bone shadow would
    // glow at night, so fall back to plain black (reads as subtle relief).
    val shadow = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) ink else Color.Black
    return this
        .drawBehind {
            val off = HARD_OFFSET.toPx()
            drawRect(color = shadow, topLeft = Offset(off, off), size = size)
        }
        .border(HARD_BORDER, ink)
        .then(
            if (accent == null) Modifier
            else Modifier.drawWithContent {
                drawContent()
                drawRect(color = accent, size = Size(size.width, ACCENT_BAR.toPx()))
            }
        )
}
