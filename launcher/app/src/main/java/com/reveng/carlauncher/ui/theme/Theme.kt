package com.reveng.carlauncher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always-dark car scheme (a head unit should never flash a white UI at the driver).
private val CarColorScheme = darkColorScheme(
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

@Composable
fun CarLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CarColorScheme,
        typography = CarTypography,
        content = content,
    )
}
