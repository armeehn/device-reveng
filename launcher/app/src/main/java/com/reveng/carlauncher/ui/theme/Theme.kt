package com.reveng.carlauncher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Applies a [CarTheme] as the Compose [MaterialTheme]. The day or night [ThemeColors]
 * variant is chosen from the vendor illumination broadcast (CarEvents.dayNight,
 * CAR_API §1.3); the resulting `MaterialTheme.colorScheme` is what every screen/card reads
 * from, so switching the active theme or crossing day/night re-themes the whole launcher.
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
    MaterialTheme(
        colorScheme = theme.variant(night).toColorScheme(),
        typography = CarTypography,
        content = content,
    )
}
