package com.ripostelabs.carlauncher.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Night mode has to be a change in *emitted light*, not a repaint. A UI audit measured the
 * Home screen at a mean 23.8/255 by day and 22.2/255 by night — 7% — while the status bar
 * icon dutifully flipped. The mechanism was there; the numbers were not.
 *
 * These tests hold the numbers, for every preset, with the WCAG 2.x relative-luminance
 * formula (the arithmetic is here rather than in Compose so a failure prints the ratio):
 *
 *  - the night field is at least [MAX_NIGHT_BACKGROUND_SHARE] darker than the day field,
 *  - the whole screen dims by at least [MAX_NIGHT_SCREEN_SHARE],
 *  - every text role still clears 4.5:1 on the surface it is drawn on, in both variants.
 *
 * A preset that pastes in a night variant from the wrong palette fails here, named.
 */
class NightModeContrastTest {

    private companion object {
        /** WCAG 1.4.3 body text. Muted text is text, so it is held to the same bar. */
        const val MIN_BODY_CONTRAST = 4.5

        /** WCAG 1.4.3 large text / 1.4.11 non-text: what an accent or an icon must clear. */
        const val MIN_ACCENT_CONTRAST = 3.0

        /**
         * A night background may keep at most 65% of its day luminance — a 35% cut.
         * Chosen because it is the smallest drop a driver reads as "the screen went dim"
         * rather than "the screen changed colour", and every preset now beats it by far
         * (the tightest is Amber at 66% off). Held as a ceiling, not a target.
         */
        const val MAX_NIGHT_BACKGROUND_SHARE = 0.65

        /**
         * The same idea for the whole panel, because the background alone was never the
         * problem: the audit's 7% came from bright text and cards on an already-dark
         * field. Weights approximate the Home screen's pixel budget — mostly field, then
         * cards, then recessed panels, then the text sitting on them. The tightest preset
         * (Tokyo Night) keeps 44%, so the 55% ceiling leaves room to re-tune a palette
         * without re-tuning the test.
         */
        const val MAX_NIGHT_SCREEN_SHARE = 0.55
        const val W_BACKGROUND = 0.55
        const val W_SURFACE = 0.30
        const val W_SURFACE_VARIANT = 0.05
        const val W_ON_SURFACE = 0.07
        const val W_ON_SURFACE_MUTED = 0.03
    }

    @Test
    fun nightFieldIsDimmer() {
        BuiltInThemes.ALL.forEach { theme ->
            val day = luminance(theme.day.background)
            val night = luminance(theme.night.background)

            assertTrue(
                "${theme.id}: night background keeps ${pct(night / day)} of the day " +
                    "luminance (max ${pct(MAX_NIGHT_BACKGROUND_SHARE)})",
                night <= day * MAX_NIGHT_BACKGROUND_SHARE,
            )
        }
    }

    @Test
    fun nightScreenIsDimmer() {
        BuiltInThemes.ALL.forEach { theme ->
            val day = screenLuminance(theme.day)
            val night = screenLuminance(theme.night)

            assertTrue(
                "${theme.id}: night screen keeps ${pct(night / day)} of the day luminance " +
                    "(max ${pct(MAX_NIGHT_SCREEN_SHARE)}) — the day/night flip is cosmetic",
                night <= day * MAX_NIGHT_SCREEN_SHARE,
            )
        }
    }

    @Test
    fun textClearsBodyContrast() {
        BuiltInThemes.ALL.forEach { theme ->
            variants(theme).forEach { (label, c) ->
                // Each pair is a colour and the surface it is actually drawn on: body text on
                // the card, the same on the field, and muted text on both the card and the
                // recessed panel it backs (onSurfaceMuted is the scheme's onSurfaceVariant).
                assertBar(label, "onBackground on background", c.onBackground, c.background)
                assertBar(label, "onSurface on surface", c.onSurface, c.surface)
                assertBar(label, "onSurfaceMuted on surface", c.onSurfaceMuted, c.surface)
                assertBar(label, "onSurfaceMuted on surfaceVariant", c.onSurfaceMuted, c.surfaceVariant)
            }
        }
    }

    @Test
    fun accentsAreReadableAndCalmer() {
        BuiltInThemes.ALL.forEach { theme ->
            variants(theme).forEach { (label, c) ->
                // Accents and the fault colour reach the driver as icons, chips and short
                // large labels, so 3:1 is their bar. Night still derives error at ~4.6:1:
                // it is the one colour the dimming rule does not cap.
                assertBar(label, "primary on surface", c.primary, c.surface, MIN_ACCENT_CONTRAST)
                assertBar(label, "error on surface", c.error, c.surface, MIN_ACCENT_CONTRAST)
            }

            // ...and the night accent never shouts louder on its own card than the day one
            // does on its. Presets that rotate their accent trio at night (Riposte) are
            // compared this way rather than colour by colour.
            val day = contrast(theme.day.primary, theme.day.surface)
            val night = contrast(theme.night.primary, theme.night.surface)

            assertTrue(
                "${theme.id}: night primary reads ${"%.2f".format(night)}:1 against its " +
                    "surface, louder than the day primary's ${"%.2f".format(day)}:1",
                night <= day,
            )
        }
    }

    private fun assertBar(
        label: String,
        pair: String,
        fg: Long,
        bg: Long,
        bar: Double = MIN_BODY_CONTRAST,
    ) {
        val ratio = contrast(fg, bg)

        assertTrue("$label: $pair reads ${"%.2f".format(ratio)}:1, under $bar:1", ratio >= bar)
    }

    /** Both variants of a theme, each tagged with the name a failure message should print. */
    private fun variants(theme: CarTheme): List<Pair<String, ThemeColors>> =
        listOf("${theme.id} (day)" to theme.day, "${theme.id} (night)" to theme.night)

    /**
     * Mean panel luminance, weighted by roughly how much of the Home screen each role
     * paints. Crude, but it is the quantity the audit measured and the one that was flat.
     */
    private fun screenLuminance(c: ThemeColors): Double =
        W_BACKGROUND * luminance(c.background) +
            W_SURFACE * luminance(c.surface) +
            W_SURFACE_VARIANT * luminance(c.surfaceVariant) +
            W_ON_SURFACE * luminance(c.onSurface) +
            W_ON_SURFACE_MUTED * luminance(c.onSurfaceMuted)

    /** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
    private fun contrast(a: Long, b: Long): Double {
        val hi = maxOf(luminance(a), luminance(b))
        val lo = minOf(luminance(a), luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    /** WCAG relative luminance of a packed 0xAARRGGBB colour (every preset is opaque). */
    private fun luminance(argb: Long): Double =
        0.2126 * linear(argb shr 16 and 0xFF) +
            0.7152 * linear(argb shr 8 and 0xFF) +
            0.0722 * linear(argb and 0xFF)

    /** sRGB electro-optical transfer function: 8-bit channel to linear light. */
    private fun linear(channel: Long): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun pct(share: Double): String = "%.0f%%".format(share * 100)
}
