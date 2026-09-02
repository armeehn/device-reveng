package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.ui.theme.BuiltInThemes
import com.ripostelabs.carlauncher.ui.theme.CarTheme
import com.ripostelabs.carlauncher.ui.theme.ThemeColors
import com.ripostelabs.carlauncher.ui.theme.ThemeStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * One codec serves two masters: [ThemeStore] persists user themes with it, and [ThemeTransfer]
 * writes and reads the JSON files a driver moves over adb. That sharing is deliberate — an
 * exported file and a stored row must never drift into two formats — which also means a break here
 * costs the driver every theme they made *and* every file they kept.
 *
 * Colours are packed ARGB longs above `Int.MAX_VALUE` (0xFF… is always set), so the width of the
 * number is load-bearing, not incidental.
 */
class ThemeCodecTest {

    private val userTheme = CarTheme(
        id = "user.1700000000000",
        name = "Workshop",
        isBuiltIn = false,
        day = ThemeColors(
            background = 0xFF101418,
            surface = 0xFF1A2028,
            surfaceVariant = 0xFF232B35,
            primary = 0xFF64B5F6,
            onBackground = 0xFFECEFF1,
            onSurface = 0xFFECEFF1,
            onSurfaceMuted = 0xFF90A4AE,
            error = 0xFFEF5350,
            accent2 = 0xFF81C784,
            accent3 = 0xFFFFB74D,
        ),
        night = ThemeColors(
            background = 0xFF05070A,
            surface = 0xFF0B0E12,
            surfaceVariant = 0xFF11161C,
            primary = 0xFF3A6E96,
            onBackground = 0xFF90A4AE,
            onSurface = 0xFF90A4AE,
            onSurfaceMuted = 0xFF5B6672,
            error = 0xFFB71C1C,
        ),
        style = ThemeStyle(cornerScale = 0.5f, monoType = true, hardEdge = true),
    )

    @Test
    fun userThemeRoundTrips() {
        assertEquals(userTheme, themeFromJson(userTheme.toJson()))
    }

    @Test
    fun everyPresetRoundTrips() {
        // Presets are never persisted, but they are exportable and they are what `duplicate` copies.
        BuiltInThemes.ALL.forEach { preset ->
            assertEquals(preset.id, preset, themeFromJson(preset.toJson()))
        }
    }

    @Test
    fun roundTripSurvivesTheTextForm() {
        // ThemeTransfer writes the object out as pretty-printed text and reads it back by parsing,
        // so the in-memory JSONObject round trip alone does not cover the export path.
        val text = userTheme.toJson().toString(2)

        assertEquals(userTheme, themeFromJson(JSONObject(text)))
    }

    @Test
    fun opaqueColoursDoNotOverflow() {
        // Every colour has alpha 0xFF set, which puts it past Int.MAX_VALUE. A codec that narrowed
        // to Int would read these back as negatives and paint the whole launcher wrong.
        val white = ThemeColors(
            background = 0xFFFFFFFF,
            surface = 0xFFFFFFFF,
            surfaceVariant = 0xFFFFFFFF,
            primary = 0xFFFFFFFF,
            onBackground = 0xFFFFFFFF,
            onSurface = 0xFFFFFFFF,
            onSurfaceMuted = 0xFFFFFFFF,
            error = 0xFFFFFFFF,
        )
        val theme = userTheme.copy(day = white, night = white)

        val decoded = themeFromJson(theme.toJson())

        assertEquals(0xFFFFFFFFL, decoded.day.background)
        assertEquals(0xFFFFFFFFL, decoded.night.primary)
    }

    @Test
    fun themeWithoutStyleGetsTheOriginalLook() {
        // Themes saved before v2.4 carry no "style" object. They must decode to the shipped
        // defaults, not to zeroed-out fields (cornerScale 0 = sharp corners everywhere).
        val json = userTheme.toJson().apply { remove("style") }

        assertEquals(ThemeStyle(), themeFromJson(json).style)
    }

    @Test
    fun partialStyleFillsTheRestFromDefaults() {
        val json = userTheme.toJson().apply {
            put("style", JSONObject().put("monoType", true))
        }

        val style = themeFromJson(json).style

        assertEquals(1f, style.cornerScale, 0f)
        assertEquals(true, style.monoType)
        assertEquals(false, style.hardEdge)
    }

    @Test
    fun fractionalCornerScaleSurvivesTheDoubleHop() {
        // cornerScale is a Float stored as a JSON double. Values that are not exactly representable
        // must still come back as the same Float.
        listOf(0f, 0.25f, 0.5f, 1f, 1.75f, 2.5f).forEach { scale ->
            val decoded = themeFromJson(userTheme.copy(style = ThemeStyle(cornerScale = scale)).toJson())

            assertEquals(scale, decoded.style.cornerScale, 0f)
        }
    }

    @Test
    fun missingAccentsDecodeAsUnset() {
        // 0 means "fall back to primary" in toColorScheme(). A theme file written before accents
        // existed has neither key, and must not decode to some other colour.
        val json = userTheme.toJson().apply {
            getJSONObject("day").remove("accent2")
            getJSONObject("day").remove("accent3")
        }

        val day = themeFromJson(json).day

        assertEquals(0L, day.accent2)
        assertEquals(0L, day.accent3)
    }

    @Test
    fun missingIsBuiltInDecodesAsAUserTheme() {
        // isBuiltIn is what makes ThemeStore.upsert and the theme editor refuse a write. A file
        // that omits it must land as editable, never as an untouchable preset.
        val json = userTheme.toJson().apply { remove("isBuiltIn") }

        assertFalse(themeFromJson(json).isBuiltIn)
    }

    @Test
    fun structurallyBrokenThemeThrows() {
        // themeFromJson is strict on the fields it cannot invent, so every caller has to guard it.
        // ThemeStore.decodeThemes and ThemeTransfer.import both do; this pins the contract they
        // rely on, so a future "make it lenient" change has to face the callers deliberately.
        val cases = listOf(
            JSONObject(),                                            // nothing at all
            JSONObject().put("id", "x").put("name", "y"),            // no colour variants
            userTheme.toJson().apply { remove("night") },            // half a theme
            userTheme.toJson().apply { getJSONObject("day").remove("primary") },
        )

        cases.forEach { json ->
            assertThrows(Exception::class.java) { themeFromJson(json) }
        }
    }
}
