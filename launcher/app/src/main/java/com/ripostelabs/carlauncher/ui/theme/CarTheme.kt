package com.ripostelabs.carlauncher.ui.theme

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
 * trivially (de)serializable for [com.ripostelabs.carlauncher.data.ThemeStore]. Reconstruct a
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
    // Optional 2nd/3rd accents (Riposte's rotating mi-parti trio). 0 = unset → falls back
    // to [primary], so pre-existing themes and serialized user themes are unaffected.
    val accent2: Long = 0,
    val accent3: Long = 0,
) {
    /**
     * Build a Material3 [ColorScheme] from these roles. The light/dark *base* is chosen
     * from the background luminance so unspecified slots (scrim, outline, inverse…) get
     * sensible defaults, then the roles we care about are copied over the top.
     */
    /**
     * The readable label colour on [accent]: whichever of the two carries more contrast.
     * WCAG asks 4.5:1 for body text; a theme whose accent clears neither is the theme's
     * problem, and the better of the two is still the right pick.
     */
    private fun onAccent(accent: Color): Color {
        val light = Color(0xFFF2F4F8)
        val dark = Color(0xFF14161A)
        return if (contrast(accent, light) >= contrast(accent, dark)) light else dark
    }

    /** WCAG relative-luminance contrast ratio, 1.0 (same) to 21.0 (black on white). */
    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    fun toColorScheme(): ColorScheme {
        val bg = Color(background)
        val base = if (bg.luminance() < 0.5f) darkColorScheme() else lightColorScheme()
        val second = Color(if (accent2 != 0L) accent2 else primary)
        val third = Color(if (accent3 != 0L) accent3 else primary)
        // Pastel accents (Catppuccin, Rosé Pine…) are light — onSurface text would wash out
        // on them, so the label is black or white by the accent itself. By CONTRAST, not by a
        // luminance threshold: the default blue sits at 0.23, under the old 0.4 cut, and the
        // white it got reads 3.4:1 on it (accessibility audit, 2026-09-22). Black reads 4.8.
        val onPrimary = onAccent(Color(primary))
        return base.copy(
            primary = Color(primary),
            secondary = second,
            onSecondary = onAccent(second),
            secondaryContainer = second.copy(alpha = 0.30f).compositeOverOpaque(bg),
            onSecondaryContainer = Color(onSurface),
            tertiary = third,
            onTertiary = onAccent(third),
            tertiaryContainer = third.copy(alpha = 0.30f).compositeOverOpaque(bg),
            onTertiaryContainer = Color(onSurface),
            onPrimary = onPrimary,
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
 * Non-color styling a theme can carry. Defaults reproduce the launcher's original look,
 * so every pre-existing theme (built-in or serialized user theme) is byte-identical.
 *
 * @param cornerScale multiplier applied to every corner radius (0 = sharp corners).
 * @param monoType    use the JetBrains Mono brand type scale instead of the system sans.
 * @param hardEdge    cards get a 2dp structural border + hard 4dp offset shadow (no blur).
 * @param themedIcons app icons are redrawn in the palette: the app's monochrome layer, or
 *                    its first letter, on a plate (see ui/icons). Off = real icons.
 */
data class ThemeStyle(
    val cornerScale: Float = 1f,
    val monoType: Boolean = false,
    val hardEdge: Boolean = false,
    val themedIcons: Boolean = false,
)

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
    val style: ThemeStyle = ThemeStyle(),
) {
    fun variant(night: Boolean): ThemeColors = if (night) this.night else this.day
}

/**
 * The presets shipped with the launcher. [DEFAULT] is the fallback active theme.
 *
 * Every night variant is derived from its own day variant by one rule, so the day/night
 * flip is a real change in emitted light rather than a repaint (a UI audit measured the
 * Home screen at 23.8 vs 22.2 of 255 — 7% — before this):
 *
 *   field/panels   hue kept, relative luminance cut to 30% of the day value and capped at
 *                  0.0030 (light presets flip onto a dark field); cards sit 2.4x and
 *                  recessed panels 3.8x above the night field so the depth survives.
 *   body text      the day palette's light pole, dimmed to 5.6:1 on the night card — day
 *                  text runs 9-18:1, so the text pixels (most of the screen's light) drop
 *                  hard while staying over the WCAG 4.5:1 body-text bar.
 *   muted text     4.9:1 on the recessed panel it is drawn on (onSurfaceVariant's pair).
 *   accents        4.6:1 on the night card, never brighter than by day, floor 3.2:1.
 *   error          4.6:1 and NOT capped: a fault must read the same at 02:00 as at noon.
 *
 * NightModeContrastTest holds every one of those numbers, so a new preset that pastes in a
 * night variant from the wrong palette fails the build instead of the driver's eyes.
 */
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
            background = 0xFF030506,
            surface = 0xFF080A0F,
            surfaceVariant = 0xFF0C0F15,
            primary = 0xFF4477D5,
            onBackground = 0xFF84898C,
            onSurface = 0xFF84898C,
            onSurfaceMuted = 0xFF77828E,
            error = 0xFFCC524C,
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
            onSurfaceMuted = 0xFF5A646D,   // 4.9:1 on Bone Dim; 7C8894 read 2.9 (audit)
            error = 0xFFC62828,
        ),
        // Even a light theme dims down at night (a head unit must never flash white).
        night = ThemeColors(
            background = 0xFF0A0A0A,
            surface = 0xFF141414,
            surfaceVariant = 0xFF1B1B1D,
            primary = 0xFF1564BD, // the day blue, a shade down: any dimmer loses the 3:1 bar
            onBackground = 0xFF8C8E8F,
            onSurface = 0xFF8C8E8F,
            onSurfaceMuted = 0xFF87898A,
            error = 0xFFC66161,
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
            background = 0xFF070603,
            surface = 0xFF110C06,
            surfaceVariant = 0xFF181108,
            primary = 0xFF9D7338,
            onBackground = 0xFF8F897E,
            onSurface = 0xFF8F897E,
            onSurfaceMuted = 0xFF957F58,
            error = 0xFFCE534C,
        ),
    )

    // ---- Rice pack: the classic Linux desktop palettes, canonical hex values. --------
    // Day = the palette as published; night = the scheme's own darker shades (crust,
    // bg0_h, bg_dark…) with dimmed text/accent, following the MIDNIGHT pattern.

    /** Catppuccin Mocha — base/surface0/surface1, mauve accent; night drops to crust/mantle. */
    val CATPPUCCIN = CarTheme(
        id = "builtin.catppuccin",
        name = "Catppuccin",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFF1E1E2E,
            surface = 0xFF313244,
            surfaceVariant = 0xFF45475A,
            primary = 0xFFCBA6F7,
            onBackground = 0xFFCDD6F4,
            onSurface = 0xFFCDD6F4,
            onSurfaceMuted = 0xFFB8BDD2,
            error = 0xFFF38BA8,
        ),
        night = ThemeColors(
            background = 0xFF0A0A12,
            surface = 0xFF13141D,
            surfaceVariant = 0xFF1A1B25,
            primary = 0xFF8C77A7,
            onBackground = 0xFF878DA2,
            onSurface = 0xFF878DA2,
            onSurfaceMuted = 0xFF858998,
            error = 0xFFB36A7E,
        ),
    )

    /** Gruvbox dark — bg/bg0_soft/bg2, yellow accent; night drops to bg0_hard. */
    val GRUVBOX = CarTheme(
        id = "builtin.gruvbox",
        name = "Gruvbox",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFF282828,
            surface = 0xFF32302F,
            surfaceVariant = 0xFF4E4744,
            primary = 0xFFFABD2F,
            onBackground = 0xFFEBDBB2,
            onSurface = 0xFFEBDBB2,
            onSurfaceMuted = 0xFFC6BDB2,
            error = 0xFFFB4934,
        ),
        night = ThemeColors(
            background = 0xFF0A0A0A,
            surface = 0xFF151414,
            surfaceVariant = 0xFF1F1B1A,
            primary = 0xFF9B7A40,
            onBackground = 0xFF988D72,
            onSurface = 0xFF988D72,
            onSurfaceMuted = 0xFF8F8980,
            error = 0xFFE04C3D,
        ),
    )

    /** Nord — nord0/1/2, frost (nord8) accent; night dims to nord10 blue. */
    val NORD = CarTheme(
        id = "builtin.nord",
        name = "Nord",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFF2E3440,
            surface = 0xFF3B4252,
            surfaceVariant = 0xFF434C5E,
            primary = 0xFF88C0D0,
            onBackground = 0xFFECEFF4,
            onSurface = 0xFFECEFF4,
            onSurfaceMuted = 0xFFBDC4CF,
            error = 0xFFC88489,   // aurora red lifted to 3.4:1 on nord1; BF616A read 2.5 (audit)
        ),
        night = ThemeColors(
            background = 0xFF08090E,
            surface = 0xFF11141B,
            surfaceVariant = 0xFF171C24,
            primary = 0xFF65848D,
            onBackground = 0xFF8C8E91,
            onSurface = 0xFF8C8E91,
            onSurfaceMuted = 0xFF848991,
            error = 0xFFBA676E,
        ),
    )

    /** Tokyo Night — bg/storm/terminal-black, blue accent; night drops to bg_dark. */
    val TOKYO_NIGHT = CarTheme(
        id = "builtin.tokyonight",
        name = "Tokyo Night",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFF1A1B26,
            surface = 0xFF24283B,
            surfaceVariant = 0xFF373D59,
            primary = 0xFF7AA2F7,
            onBackground = 0xFFC0CAF5,
            onSurface = 0xFFC0CAF5,
            onSurfaceMuted = 0xFFACAFC3,
            error = 0xFFF7768E,
        ),
        night = ThemeColors(
            background = 0xFF090A10,
            surface = 0xFF111420,
            surfaceVariant = 0xFF181B2A,
            primary = 0xFF667FB7,
            onBackground = 0xFF858DAB,
            onSurface = 0xFF858DAB,
            onSurfaceMuted = 0xFF868898,
            error = 0xFFC26273,
        ),
    )

    /** Dracula — bg/lighter-bg/selection, purple accent. */
    val DRACULA = CarTheme(
        id = "builtin.dracula",
        name = "Dracula",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFF282A36,
            surface = 0xFF343746,
            surfaceVariant = 0xFF44475A,
            primary = 0xFFBD93F9,
            onBackground = 0xFFF8F8F2,
            onSurface = 0xFFF8F8F2,
            onSurfaceMuted = 0xFFB9BDD0,
            error = 0xFFFF5555,
        ),
        night = ThemeColors(
            background = 0xFF09090F,
            surface = 0xFF13141C,
            surfaceVariant = 0xFF1A1C25,
            primary = 0xFF8E73B5,
            onBackground = 0xFF8F8F8B,
            onSurface = 0xFF8F8F8B,
            onSurfaceMuted = 0xFF868998,
            error = 0xFFDA5151,
        ),
    )

    /** Rosé Pine — base/surface/overlay, rose accent, love for errors. */
    val ROSE_PINE = CarTheme(
        id = "builtin.rosepine",
        name = "Rosé Pine",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFF191724,
            surface = 0xFF1F1D2E,
            surfaceVariant = 0xFF26233A,
            primary = 0xFFEBBCBA,
            onBackground = 0xFFE0DEF4,
            onSurface = 0xFFE0DEF4,
            onSurfaceMuted = 0xFF908CAA,
            error = 0xFFEB6F92,
        ),
        night = ThemeColors(
            background = 0xFF0A0810,
            surface = 0xFF14131F,
            surfaceVariant = 0xFF1B182B,
            primary = 0xFF937A78,
            onBackground = 0xFF8E8C9B,
            onSurface = 0xFF8E8C9B,
            onSurfaceMuted = 0xFF8985A2,
            error = 0xFFC1617B,
        ),
    )

    /** Phosphor — green-on-black CRT terminal; night dims the glow right down. */
    val PHOSPHOR = CarTheme(
        id = "builtin.phosphor",
        name = "Phosphor",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFF040A04,
            surface = 0xFF0A120A,
            surfaceVariant = 0xFF0F1E0F,
            primary = 0xFF33FF66,
            onBackground = 0xFF9BFF9B,
            onSurface = 0xFF9BFF9B,
            onSurfaceMuted = 0xFF3FA53F,
            error = 0xFFFF4444,
        ),
        night = ThemeColors(
            background = 0xFF010301,
            surface = 0xFF030703,
            surfaceVariant = 0xFF040B04,
            primary = 0xFF3C8848,
            onBackground = 0xFF589458,
            onSurface = 0xFF589458,
            onSurfaceMuted = 0xFF368F36,
            error = 0xFFD64343,
        ),
    )

    /**
     * "Riposte" — the Riposte Laboratories brand system (ripostelabs.xyz/brand,
     * DOC NO. RL-BRAND-001 REV. A): ink on bone, JetBrains Mono, radius 0, hard offset
     * shadows, and the rotating Pink → Marigold → Teal accent trio.
     *
     * Day is the literal printed-document palette (Ink #1D1A17 on Bone #F6F1E7; Pink Deep
     * as primary since bright pink fails WCAG for text). Night flips onto the brand's ink
     * field (Ink / Ink Raised panels) with dimmed bone text and a desaturated low-blue
     * (marigold-led) accent rotation, per the night-driving rules in LAUNCHER_DESIGN §1.3.
     */
    val RIPOSTE = CarTheme(
        id = "builtin.riposte",
        name = "Riposte",
        isBuiltIn = true,
        style = ThemeStyle(cornerScale = 0f, monoType = true, hardEdge = true, themedIcons = true),
        day = ThemeColors(
            background = 0xFFF6F1E7, // Bone
            surface = 0xFFF6F1E7, // cards are bone too — the 2dp ink border separates
            surfaceVariant = 0xFFEAE4D6, // Bone Dim (recessed panels)
            primary = 0xFFD81150, // Pink Deep (AA 4.53:1 on bone)
            onBackground = 0xFF1D1A17, // Ink
            onSurface = 0xFF1D1A17,
            onSurfaceMuted = 0xFF5C554C, // Ink Line
            error = 0xFFB3261E,
            accent2 = 0xFF12B795, // Riposte Teal (fills/bars)
            accent3 = 0xFFFE9A0D, // Riposte Marigold (8.12:1 — ink-text safe)
        ),
        night = ThemeColors(
            background = 0xFF0A0A09, // ink field, dimmed for night
            surface = 0xFF151513, // Ink, dimmed
            surfaceVariant = 0xFF1D1B19, // Ink Raised, dimmed
            primary = 0xFFA96E39, // dimmed marigold — low-blue night accent
            onBackground = 0xFF928E88, // dimmed bone
            onSurface = 0xFF928E88, // dimmed bone
            onSurfaceMuted = 0xFF8B8883, // dimmed bone, muted
            error = 0xFFB96968,
            accent2 = 0xFF3C8D77, // dimmed teal
            accent3 = 0xFFC13353, // dimmed pink
        ),
    )

    /**
     * "Riposte Ink" — the two-ink briefing look of ripostelabs.xyz (site.css, 2026-09):
     * Ink #1D1A17 and Bone #F6F1E7 and nothing else. No accent trio: `accent2`/`accent3`
     * are left unset so every accent slot falls back to the primary, which *is* the ink.
     * A selected chip is therefore ink with bone text by day, and the reverse by night,
     * exactly as `.chip` / `.dark .chip` render on the site. Dim text is the ink at the
     * site's `.dim`/`.tag` opacity (~0.7), pre-composited so the value stays opaque.
     *
     * Night keeps the dimmed-bone night-driving rule of [RIPOSTE]. `error` is the one
     * colour kept: a car UI must show a fault as red even when the brand shows none.
     */
    val RIPOSTE_INK = CarTheme(
        id = "builtin.riposte_ink",
        name = "Riposte Ink",
        isBuiltIn = true,
        style = ThemeStyle(cornerScale = 0f, monoType = true, hardEdge = true, themedIcons = true),
        day = ThemeColors(
            background = 0xFFF6F1E7, // Bone
            surface = 0xFFF6F1E7, // cards are bone too — the 2dp ink rule separates
            surfaceVariant = 0xFFEAE4D6, // Bone Dim (recessed panels)
            primary = 0xFF1D1A17, // Ink — the only accent
            onBackground = 0xFF1D1A17,
            onSurface = 0xFF1D1A17,
            onSurfaceMuted = 0xFF5E5A55, // Ink at 0.7 over bone
            error = 0xFFB3261E,
        ),
        night = ThemeColors(
            background = 0xFF0A0A09, // ink field, dimmed for night
            surface = 0xFF151513, // Ink, dimmed
            surfaceVariant = 0xFF1D1B19, // Ink Raised, dimmed
            primary = 0xFF928E88, // dimmed bone — the only accent
            onBackground = 0xFF928E88, // dimmed bone
            onSurface = 0xFF928E88, // dimmed bone
            onSurfaceMuted = 0xFF8B8883, // dimmed bone at 0.7 over ink
            error = 0xFFB96968,
        ),
    )

    val DEFAULT: CarTheme = MIDNIGHT

    val ALL: List<CarTheme> = listOf(
        MIDNIGHT, DAYLIGHT, AMBER,
        CATPPUCCIN, GRUVBOX, NORD, TOKYO_NIGHT, DRACULA, ROSE_PINE, PHOSPHOR,
        RIPOSTE, RIPOSTE_INK,
    )
}
