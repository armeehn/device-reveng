package com.reveng.carlauncher.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Applies a [CarTheme] as the Compose [MaterialTheme]. The day or night [ThemeColors]
 * variant is chosen from the vendor illumination broadcast (CarEvents.dayNight,
 * CAR_API §1.3); the resulting `MaterialTheme.colorScheme` is what every screen/card reads
 * from, so switching the active theme or crossing day/night re-themes the whole launcher.
 *
 * v1.0: instead of hard-swapping the palette, every color role is *animated* to its new
 * value (see [animatedColorScheme]). Crossing day↔night, or switching themes, now fades the
 * whole UI to the new colours over [CROSSFADE_MS] rather than snapping — no white flash, no
 * jarring jump on a head unit.
 *
 * @param theme the active theme (defaults to the built-in "Midnight" preset so the UI is
 *              correctly themed before ThemeStore has emitted anything).
 * @param night when true, use the theme's dimmed night variant.
 */
@Composable
fun CarLauncherTheme(
    theme: CarTheme = BuiltInThemes.DEFAULT,
    night: Boolean = false,
    content: @Composable () -> Unit,
) {
    val target = theme.variant(night).toColorScheme()
    // v2.4: themes carry a ThemeStyle (corner scale / brand mono type / hard-edge cards).
    // It is provided as LocalCarStyle for carShape()/carCard(), and drives the Material
    // typography + component shapes so styles apply app-wide with no per-screen changes.
    CompositionLocalProvider(LocalCarStyle provides theme.style) {
        MaterialTheme(
            colorScheme = animatedColorScheme(target),
            typography = if (theme.style.monoType) MonoTypography else CarTypography,
            shapes = carShapes(theme.style.cornerScale),
            content = content,
        )
    }
}

/** Day/night (and theme-switch) colour crossfade duration, in milliseconds. */
const val CROSSFADE_MS = 420

/**
 * Returns a [ColorScheme] whose visible roles are each driven by an [animateColorAsState],
 * so a change in [target] animates smoothly instead of snapping. Only the roles the launcher
 * actually paints with are animated; the rest are copied through from [target] unchanged.
 */
@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = CROSSFADE_MS)
    return target.copy(
        primary = animateColorAsState(target.primary, spec, label = "primary").value,
        onPrimary = animateColorAsState(target.onPrimary, spec, label = "onPrimary").value,
        primaryContainer = animateColorAsState(target.primaryContainer, spec, label = "primaryContainer").value,
        onPrimaryContainer = animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer").value,
        secondary = animateColorAsState(target.secondary, spec, label = "secondary").value,
        tertiary = animateColorAsState(target.tertiary, spec, label = "tertiary").value,
        background = animateColorAsState(target.background, spec, label = "background").value,
        onBackground = animateColorAsState(target.onBackground, spec, label = "onBackground").value,
        surface = animateColorAsState(target.surface, spec, label = "surface").value,
        onSurface = animateColorAsState(target.onSurface, spec, label = "onSurface").value,
        surfaceVariant = animateColorAsState(target.surfaceVariant, spec, label = "surfaceVariant").value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, spec, label = "onSurfaceVariant").value,
        error = animateColorAsState(target.error, spec, label = "error").value,
        outline = animateColorAsState(target.outline, spec, label = "outline").value,
    )
}
