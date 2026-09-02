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
    fun toColorScheme(): ColorScheme {
        val bg = Color(background)
        val base = if (bg.luminance() < 0.5f) darkColorScheme() else lightColorScheme()
        val second = Color(if (accent2 != 0L) accent2 else primary)
        val third = Color(if (accent3 != 0L) accent3 else primary)
        // Pastel accents (Catppuccin, Rosé Pine…) are light — onSurface text would wash
        // out on them, so pick black/white by the accent's own luminance instead.
        val onPrimary =
            if (Color(primary).luminance() < 0.4f) Color(0xFFF2F4F8) else Color(0xFF14161A)
        return base.copy(
            primary = Color(primary),
            secondary = second,
            onSecondary = Color(surface),
            secondaryContainer = second.copy(alpha = 0.30f).compositeOverOpaque(bg),
            onSecondaryContainer = Color(onSurface),
            tertiary = third,
            onTertiary = Color(surface),
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
            onSurfaceMuted = 0xFFA6ADC8,
            error = 0xFFF38BA8,
        ),
        night = ThemeColors(
            background = 0xFF11111B,
            surface = 0xFF181825,
            surfaceVariant = 0xFF181825,
            primary = 0xFF9576C4,
            onBackground = 0xFFA6ADC8,
            onSurface = 0xFFA6ADC8,
            onSurfaceMuted = 0xFF6C7086,
            error = 0xFFF38BA8,
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
            surfaceVariant = 0xFF504945,
            primary = 0xFFFABD2F,
            onBackground = 0xFFEBDBB2,
            onSurface = 0xFFEBDBB2,
            onSurfaceMuted = 0xFFA89984,
            error = 0xFFFB4934,
        ),
        night = ThemeColors(
            background = 0xFF1D2021,
            surface = 0xFF282828,
            surfaceVariant = 0xFF282828,
            primary = 0xFFD79921,
            onBackground = 0xFFA89984,
            onSurface = 0xFFA89984,
            onSurfaceMuted = 0xFF7C6F64,
            error = 0xFFCC241D,
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
            onSurfaceMuted = 0xFF8C99AF,
            error = 0xFFBF616A,
        ),
        night = ThemeColors(
            background = 0xFF242933,
            surface = 0xFF2E3440,
            surfaceVariant = 0xFF2E3440,
            primary = 0xFF5E81AC,
            onBackground = 0xFF8C99AF,
            onSurface = 0xFF8C99AF,
            onSurfaceMuted = 0xFF616E88,
            error = 0xFFBF616A,
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
            surfaceVariant = 0xFF414868,
            primary = 0xFF7AA2F7,
            onBackground = 0xFFC0CAF5,
            onSurface = 0xFFC0CAF5,
            onSurfaceMuted = 0xFF737AA2,
            error = 0xFFF7768E,
        ),
        night = ThemeColors(
            background = 0xFF16161E,
            surface = 0xFF1A1B26,
            surfaceVariant = 0xFF1A1B26,
            primary = 0xFF3D59A1,
            onBackground = 0xFF737AA2,
            onSurface = 0xFF737AA2,
            onSurfaceMuted = 0xFF565F89,
            error = 0xFFDB4B4B,
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
            onSurfaceMuted = 0xFF6272A4,
            error = 0xFFFF5555,
        ),
        night = ThemeColors(
            background = 0xFF1E1F29,
            surface = 0xFF282A36,
            surfaceVariant = 0xFF282A36,
            primary = 0xFF7B5FAE,
            onBackground = 0xFF9DA0B0,
            onSurface = 0xFF9DA0B0,
            onSurfaceMuted = 0xFF6272A4,
            error = 0xFFFF5555,
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
            background = 0xFF100E17,
            surface = 0xFF191724,
            surfaceVariant = 0xFF191724,
            primary = 0xFFAD8A88,
            onBackground = 0xFF908CAA,
            onSurface = 0xFF908CAA,
            onSurfaceMuted = 0xFF6E6A86,
            error = 0xFFEB6F92,
        ),
    )

    /** Phosphor — green-on-black CRT terminal; night dims the glow right down. */
    val PHOSPHOR = CarTheme(
        id = "builtin.phosphor",
        name = "Phosphor",
        isBuiltIn = true,
        day = ThemeColors(
            background = 0xFF000000,
            surface = 0xFF0A120A,
            surfaceVariant = 0xFF0F1E0F,
            primary = 0xFF33FF66,
            onBackground = 0xFF9BFF9B,
            onSurface = 0xFF9BFF9B,
            onSurfaceMuted = 0xFF3FA53F,
            error = 0xFFFF4444,
        ),
        night = ThemeColors(
            background = 0xFF000000,
            surface = 0xFF071007,
            surfaceVariant = 0xFF071007,
            primary = 0xFF1FA046,
            onBackground = 0xFF3FA53F,
            onSurface = 0xFF3FA53F,
            onSurfaceMuted = 0xFF2A7A2A,
            error = 0xFFCC3333,
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
            background = 0xFF14110E, // ink field, dimmed below brand Ink for night
            surface = 0xFF1D1A17, // Ink
            surfaceVariant = 0xFF241F1B, // Ink Raised
            primary = 0xFFC9891F, // dimmed marigold — low-blue night accent
            onBackground = 0xFFCFC7B8, // dimmed bone
            onSurface = 0xFFCFC7B8,
            onSurfaceMuted = 0xFF8A8172,
            error = 0xFFCC4A44,
            accent2 = 0xFF17806A, // dimmed teal
            accent3 = 0xFF9C3555, // dimmed pink
        ),
    )

    val DEFAULT: CarTheme = MIDNIGHT

    val ALL: List<CarTheme> = listOf(
        MIDNIGHT, DAYLIGHT, AMBER,
        CATPPUCCIN, GRUVBOX, NORD, TOKYO_NIGHT, DRACULA, ROSE_PINE, PHOSPHOR,
        RIPOSTE,
    )
}
