package com.ripostelabs.carlauncher.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Theme ids are the persistence key: ThemeStore saves the active id and resolves it back out of
 * `BuiltInThemes.ALL + userThemes`. A duplicate or a preset missing from [BuiltInThemes.ALL] is
 * therefore not a cosmetic slip — it silently resolves the user's chosen theme to something else
 * on the next boot, and there is nothing in the type system to stop either.
 *
 * These are cheap table invariants over a list that grows every time a palette is added.
 */
class BuiltInThemesTest {

    @Test
    fun idsAreUnique() {
        val ids = BuiltInThemes.ALL.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun idsAndNamesArePresent() {
        BuiltInThemes.ALL.forEach { theme ->
            assertTrue("blank id in ${theme.name}", theme.id.isNotBlank())
            assertTrue("blank name for ${theme.id}", theme.name.isNotBlank())
            // The prefix separates presets from the "user.<millis>" ids ThemeStore mints.
            assertTrue("unprefixed id ${theme.id}", theme.id.startsWith("builtin."))
        }
    }

    @Test
    fun presetsAreImmutable() {
        // isBuiltIn is what makes ThemeStore.upsert and the editor refuse to write a preset.
        BuiltInThemes.ALL.forEach { theme ->
            assertTrue("${theme.id} is editable", theme.isBuiltIn)
        }
    }

    @Test
    fun defaultIsInTheList() {
        // ThemeStore falls back to DEFAULT whenever an id does not resolve. If DEFAULT itself is
        // not in ALL, that fallback lands on a theme the Themes screen cannot show as selected.
        assertNotNull(BuiltInThemes.ALL.firstOrNull { it.id == BuiltInThemes.DEFAULT.id })
    }

    @Test
    fun variantPicksDayOrNight() {
        val theme = BuiltInThemes.MIDNIGHT

        assertEquals(theme.day, theme.variant(night = false))
        assertEquals(theme.night, theme.variant(night = true))
    }

    @Test
    fun nightVariantIsDarker() {
        // A head unit must never flash white at night, so every preset — including the light
        // ones — dims its background down. Summing the three channels is a crude brightness
        // measure, but it is enough to catch a night variant pasted in from the wrong palette.
        BuiltInThemes.ALL.forEach { theme ->
            assertTrue(
                "${theme.id} night background is not darker than day",
                channelSum(theme.night.background) <= channelSum(theme.day.background),
            )
        }
    }

    /** R + G + B of a packed 0xAARRGGBB colour, ignoring alpha (every preset is opaque). */
    private fun channelSum(argb: Long): Long =
        (argb shr 16 and 0xFF) + (argb shr 8 and 0xFF) + (argb and 0xFF)

    @Test
    fun defaultStyleIsTheOriginalLook() {
        // ThemeStyle's defaults must keep every pre-v2.4 serialized theme byte-identical.
        val style = ThemeStyle()

        assertEquals(1f, style.cornerScale, 0f)
        assertFalse(style.monoType)
        assertFalse(style.hardEdge)
        assertFalse(style.themedIcons)
    }

    @Test
    fun riposteCarriesBrandStyle() {
        // RL-BRAND-001: radius 0, JetBrains Mono, hard offset shadows. The brand theme is the
        // one preset whose style is load-bearing rather than taste.
        val style = BuiltInThemes.RIPOSTE.style

        assertEquals(0f, style.cornerScale, 0f)
        assertTrue(style.monoType)
        assertTrue(style.hardEdge)
        // Themed icons are part of the brand look: every tile in the palette, none full-colour.
        assertTrue(style.themedIcons)
    }

    @Test
    fun onlyRiposteCarriesAccents() {
        // accent2/accent3 default to 0, which toColorScheme() reads as "fall back to primary".
        // A stray non-zero on another preset would quietly re-tint its containers.
        BuiltInThemes.ALL.filter { it.id != BuiltInThemes.RIPOSTE.id }.forEach { theme ->
            assertEquals("${theme.id} sets accent2", 0L, theme.day.accent2)
            assertEquals("${theme.id} sets accent3", 0L, theme.day.accent3)
        }

        assertTrue(BuiltInThemes.RIPOSTE.day.accent2 != 0L)
        assertTrue(BuiltInThemes.RIPOSTE.day.accent3 != 0L)
    }

    @Test
    fun proximityRampIsDistinct() {
        // Safety-adjacent: the parking aids encode clear/near/close in colour. If two steps
        // collapse to one colour (tertiary falls back to primary in 10 of 11 presets), the
        // "near" warning never shows. Every preset, both variants.
        BuiltInThemes.ALL.forEach { theme ->
            listOf(false, true).forEach { night ->
                val ramp = proximityRamp(theme.variant(night).toColorScheme())
                val steps = listOf(ramp.clear, ramp.near, ramp.close)

                assertEquals(
                    "${theme.id} (night=$night) ramp steps collapse: $steps",
                    steps.size,
                    steps.toSet().size,
                )
            }
        }
    }
}
