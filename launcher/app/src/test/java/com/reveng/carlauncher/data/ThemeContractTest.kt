package com.reveng.carlauncher.data

import com.reveng.carlauncher.ui.theme.ThemeColors
import com.reveng.carlauncher.ui.theme.ThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.5 — the published palette is read by separate packages, so the row is a wire format:
 * a column that moves or a fallback that changes silently repaints twenty-six apps wrong.
 */
class ThemeContractTest {

    private val colors = ThemeColors(
        background = 0xFF0B0E11,
        surface = 0xFF161B22,
        surfaceVariant = 0xFF1F2630,
        primary = 0xFF2F81F7,
        onBackground = 0xFFE6EDF3,
        onSurface = 0xFFE6EDF3,
        onSurfaceMuted = 0xFF8B98A5,
        error = 0xFFE5534B,
    )

    private fun snapshot(c: ThemeColors = colors, night: Boolean = false) =
        ThemeContract.Snapshot("builtin.midnight", "Midnight", night, c)

    private fun value(name: String, c: ThemeColors = colors, night: Boolean = false): Any =
        ThemeContract.row(snapshot(c, night))[ThemeContract.COLUMNS.indexOf(name)]

    @Test
    fun `row is one value per column`() {
        assertEquals(ThemeContract.COLUMNS.size, ThemeContract.row(snapshot()).size)
    }

    @Test
    fun `columns are unique`() {
        assertEquals(ThemeContract.COLUMNS.size, ThemeContract.COLUMNS.toSet().size)
    }

    @Test
    fun `identity and colours land in their own columns`() {
        assertEquals("builtin.midnight", value(ThemeContract.COL_THEME_ID))
        assertEquals("Midnight", value(ThemeContract.COL_THEME_NAME))
        assertEquals(0xFF0B0E11, value(ThemeContract.COL_BACKGROUND))
        assertEquals(0xFF2F81F7, value(ThemeContract.COL_PRIMARY))
        assertEquals(0xFF8B98A5, value(ThemeContract.COL_ON_SURFACE_MUTED))
    }

    /** SQLite has no boolean; a client reads an int. */
    @Test
    fun `night is published as an int flag`() {
        assertEquals(0, value(ThemeContract.COL_NIGHT))
        assertEquals(1, value(ThemeContract.COL_NIGHT, night = true))
    }

    /**
     * Unset optional accents are stored as 0 and resolved to `primary` here, so a client can
     * paint a three-accent theme and a one-accent theme with identical code — and never paints
     * a transparent black it read as a real colour.
     */
    @Test
    fun `unset accents fall back to primary`() {
        assertEquals(colors.primary, value(ThemeContract.COL_ACCENT2))
        assertEquals(colors.primary, value(ThemeContract.COL_ACCENT3))
    }

    @Test
    fun `set accents are published as themselves`() {
        val trio = colors.copy(accent2 = 0xFFE5A0FF, accent3 = 0xFF7BE0A4)
        assertEquals(0xFFE5A0FF, value(ThemeContract.COL_ACCENT2, trio))
        assertEquals(0xFF7BE0A4, value(ThemeContract.COL_ACCENT3, trio))
    }

    /**
     * A snapshot built without a style — every publisher that predates v0.8 — must publish the
     * neutral `ThemeStyle()` values, so a themed suite app renders exactly what an unstyled
     * launcher renders.
     */
    @Test
    fun `style columns default to the neutral style`() {
        assertEquals(1f, value(ThemeContract.COL_CORNER_SCALE))
        assertEquals(0, value(ThemeContract.COL_MONO_TYPE))
        assertEquals(0, value(ThemeContract.COL_HARD_EDGE))
    }

    /** Boolean style flags travel as int 0/1, the same convention as [ThemeContract.COL_NIGHT]. */
    @Test
    fun `a styled theme publishes its style in the right columns`() {
        val styled = ThemeContract.Snapshot(
            "builtin.riposte",
            "Riposte",
            false,
            colors,
            ThemeStyle(cornerScale = 0f, monoType = true, hardEdge = true),
        )
        val row = ThemeContract.row(styled)
        assertEquals(0f, row[ThemeContract.COLUMNS.indexOf(ThemeContract.COL_CORNER_SCALE)])
        assertEquals(1, row[ThemeContract.COLUMNS.indexOf(ThemeContract.COL_MONO_TYPE)])
        assertEquals(1, row[ThemeContract.COLUMNS.indexOf(ThemeContract.COL_HARD_EDGE)])
    }
}
