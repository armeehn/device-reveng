package com.ripostelabs.carlauncher.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The accent is read as text: labels on cards, and by the suite apps, which take it from
 * the launcher theme. A suite audit (rav4-apps #76) measured Midnight's #2F81F7 at 4.07:1
 * on surfaceVariant. So every built-in primary must clear WCAG 1.4.3 body text on each
 * surface it can sit on, and the label drawn on it (onPrimary) must clear it too.
 *
 * accent2/accent3 are fills and bars (Riposte's trio), held to 3:1 elsewhere, not here.
 */
class AccentContrastTest {

    private companion object {
        /** WCAG 1.4.3 body text. */
        const val MIN_TEXT_CONTRAST = 4.5

        /** Packed ARGB is a Long here; toArgb() is a signed Int. */
        const val ARGB_MASK = 0xFFFFFFFFL
    }

    @Test
    fun accentReadsOnEverySurface() {
        forEachVariant { label, c ->
            assertBar(label, "primary on background", c.primary, c.background)
            assertBar(label, "primary on surface", c.primary, c.surface)
            assertBar(label, "primary on surfaceVariant", c.primary, c.surfaceVariant)
        }
    }

    @Test
    fun labelReadsOnAccent() {
        forEachVariant { label, c ->
            val onPrimary = c.toColorScheme().onPrimary.toArgb().toLong() and ARGB_MASK

            assertBar(label, "onPrimary on primary", onPrimary, c.primary)
        }
    }

    private fun forEachVariant(check: (String, ThemeColors) -> Unit) {
        BuiltInThemes.ALL.forEach { theme ->
            check("${theme.id} (day)", theme.day)
            check("${theme.id} (night)", theme.night)
        }
    }

    private fun assertBar(label: String, pair: String, fg: Long, bg: Long) {
        val ratio = contrast(fg, bg)

        assertTrue(
            "$label: $pair reads ${"%.2f".format(ratio)}:1, under $MIN_TEXT_CONTRAST:1",
            ratio >= MIN_TEXT_CONTRAST,
        )
    }

    /** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
    private fun contrast(a: Long, b: Long): Double {
        val hi = maxOf(luminance(a), luminance(b))
        val lo = minOf(luminance(a), luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    /** WCAG relative luminance of a packed 0xAARRGGBB colour. */
    private fun luminance(argb: Long): Double =
        0.2126 * linear(argb shr 16 and 0xFF) +
            0.7152 * linear(argb shr 8 and 0xFF) +
            0.0722 * linear(argb and 0xFF)

    /** sRGB transfer function: 8-bit channel to linear light. */
    private fun linear(channel: Long): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
}
