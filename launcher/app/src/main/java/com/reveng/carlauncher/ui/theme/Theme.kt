package com.reveng.carlauncher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Day scheme — the default dark car palette.
private val DayColorScheme = darkColorScheme(
    primary = CarAccent,
    onPrimary = CarOnSurface,
    primaryContainer = CarAccentMuted,
    onPrimaryContainer = CarOnSurface,
    background = CarBackground,
    onBackground = CarOnSurface,
    surface = CarSurface,
    onSurface = CarOnSurface,
    surfaceVariant = CarSurfaceVariant,
    onSurfaceVariant = CarOnSurfaceMuted,
    error = CarError,
)

// Night scheme — dimmer + softer, to reduce glare after dark (still never white).
private val NightColorScheme = darkColorScheme(
    primary = CarAccentNight,
    onPrimary = CarOnSurface,
    primaryContainer = CarAccentMuted,
    onPrimaryContainer = CarOnSurfaceMuted,
    background = CarBackgroundNight,
    onBackground = CarOnSurfaceMuted,
    surface = CarSurfaceNight,
    onSurface = CarOnSurfaceMuted,
    surfaceVariant = CarSurfaceNight,
    onSurfaceVariant = CarOnSurfaceDim,
    error = CarError,
)

/**
 * @param night when true, use the dimmed night palette. Wired to the vendor day/night
 *              illumination broadcast via `CarEvents.dayNight` (CAR_API §1.3); defaults to
 *              day so the theme is correct before the first broadcast arrives.
 */
@Composable
fun CarLauncherTheme(night: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (night) NightColorScheme else DayColorScheme,
        typography = CarTypography,
        content = content,
    )
}
