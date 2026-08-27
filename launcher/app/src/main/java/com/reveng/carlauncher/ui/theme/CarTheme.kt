package com.reveng.carlauncher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The eight color roles a launcher theme exposes. Each maps onto a
 * [androidx.compose.material3.ColorScheme] slot so every existing screen picks the
 * theme up through `MaterialTheme.colorScheme` with no per-screen changes.
 *
 * Colors are stored as packed ARGB [Long]s (e.g. `0xFF0B0E11`) so the whole theme is
 * trivially (de)serializable for [com.reveng.carlauncher.data.ThemeStore]. Reconstruct a
 * Compose [Color] with `Color(value)` — the `Color(Long)` constructor treats the low 32
 * bits as ARGB.
 */
data class ThemeColors(
    val background: Long,
    val surface: Long,
    val surfaceVariant: Long,
    val primary: Long,
    val onBackground: Long,
    val onSurface: Long,
    val onSurfaceMuted: Long,
    val error: Long,
) {
    /**
     * Build a Material3 [ColorScheme] from these roles. The light/dark *base* is chosen
     * from the background luminance so unspecified slots (scrim, outline, inverse…) get
     * sensible defaults, then the roles we care about are copied over the top.
     */
    fun toColorScheme(): ColorScheme {
        val bg = Color(background)
        val base = if (bg.luminance() < 0.5f) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = Color(primary),
            onPrimary = Color(onSurface),
            primaryContainer = Color(primary).copy(alpha = 0.30f).compositeOverOpaque(bg),
            onPrimaryContainer = Color(onSurface),
            background = bg,
            onBackground = Color(onBackground),
            surface = Color(surface),
            onSurface = Color(onSurface),
            surfaceVariant = Color(surfaceVariant),
            onSurfaceVariant = Color(onSurfaceMuted),
            error = Color(error),
            outline = Color(onSurfaceMuted).copy(alpha = 0.5f),
        )
    }
}

/** Flatten a translucent color onto an opaque background so container tints stay opaque. */
private fun Color.compositeOverOpaque(bg: Color): Color {
    val a = alpha
    return Color(
        red = red * a + bg.red * (1 - a),
        green = green * a + bg.green * (1 - a),
        blue = blue * a + bg.blue * (1 - a),
        alpha = 1f,
    )
}

/**
 * A named, switchable launcher color theme. Carries separate [day] and [night] variants
 * so a theme still honours the vendor illumination broadcast (CarEvents.dayNight); a
 * theme that wants no day/night distinction simply uses identical variants.
 *
 * @param isBuiltIn presets shipped with the app — cannot be edited or deleted, only
 *                  duplicated into an editable user theme.
 */
data class CarTheme(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean,
    val day: ThemeColors,
    val night: ThemeColors,
) {
    fun variant(night: Boolean): ThemeColors = if (night) this.night else this.day
}

/** The presets shipped with the launcher. [DEFAULT] is the fallback active theme. */
object BuiltInThemes {

    /** "Midnight" — the original v0.2 dark car palette (day) + dimmed night variant. */
    val MIDNIGHT = CarTheme(
        id = "builtin.midnight",
        name = "Midnight",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFF0B0E11,
            surface = 0xFF161B22,
            surfaceVariant = 0xFF1F2630,
            primary = 0xFF2F81F7,
            onBackground = 0xFFE6EDF3,
            onSurface = 0xFFE6EDF3,
            onSurfaceMuted = 0xFF8B98A5,
            error = 0xFFE5534B,
        ),
        night = ThemeColors(
            background = 0xFF05070A,
            surface = 0xFF0D1117,
            surfaceVariant = 0xFF0D1117,
            primary = 0xFF1F5FB0,
            onBackground = 0xFF8B98A5,
            onSurface = 0xFF8B98A5,
            onSurfaceMuted = 0xFF5B6672,
            error = 0xFFE5534B,
        ),
    )

    /** "Daylight" — a bright, high-contrast light theme for daytime driving. */
    val DAYLIGHT = CarTheme(
        id = "builtin.daylight",
        name = "Daylight",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFFF4F6F8,
            surface = 0xFFFFFFFF,
            surfaceVariant = 0xFFE3E8EE,
            primary = 0xFF1565C0,
            onBackground = 0xFF10141A,
            onSurface = 0xFF10141A,
            onSurfaceMuted = 0xFF5B6672,
            error = 0xFFC62828,
        ),
        // Even a light theme dims down at night (a head unit must never flash white).
        night = ThemeColors(
            background = 0xFF11161C,
            surface = 0xFF1B222B,
            surfaceVariant = 0xFF262F3A,
            primary = 0xFF5B9BE0,
            onBackground = 0xFFDDE4EC,
            onSurface = 0xFFDDE4EC,
            onSurfaceMuted = 0xFF8B98A5,
            error = 0xFFE5534B,
        ),
    )

    /** "Amber" — a warm, colored-accent dark theme. */
    val AMBER = CarTheme(
        id = "builtin.amber",
        name = "Amber",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFF14100A,
            surface = 0xFF211A10,
            surfaceVariant = 0xFF2E2415,
            primary = 0xFFFFB300,
            onBackground = 0xFFF3E9D8,
            onSurface = 0xFFF3E9D8,
            onSurfaceMuted = 0xFFB39A6B,
            error = 0xFFE5534B,
        ),
        night = ThemeColors(
            background = 0xFF0B0805,
            surface = 0xFF17110A,
            surfaceVariant = 0xFF17110A,
            primary = 0xFFC98A1E,
            onBackground = 0xFFB39A6B,
            onSurface = 0xFFB39A6B,
            onSurfaceMuted = 0xFF7A6944,
            error = 0xFFE5534B,
        ),
    )

    val DEFAULT: CarTheme = MIDNIGHT

    val ALL: List<CarTheme> = listOf(MIDNIGHT, DAYLIGHT, AMBER)
}
