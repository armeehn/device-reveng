package com.reveng.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LauncherSettings]' defaults are what a fresh side-load runs on, and they are also what every
 * unset key falls back to for the life of the install — DataStore stores only the keys that were
 * written, so a default is not a first-run value, it is the standing answer.
 *
 * Three of them are safety decisions rather than taste, and each is defaulted the way it is for a
 * reason recorded in the class: the motion gate is on so it must be opted *out* of, the radar
 * layout is unconfirmed so nothing may present a guessed decode as a safety claim, and the vendor
 * system bars are left alone so a non-rooted unit is never left with no bars at all.
 */
class LauncherSettingsTest {

    @Test
    fun safetyDefaultsFailClosed() {
        val defaults = LauncherSettings()

        assertTrue("the parked-only gate must be on by default", defaults.motionGateEnabled)
        assertFalse("a guessed radar layout must not be trusted by default", defaults.radarLayoutConfirmed)
        assertFalse("suppressing the vendor bars needs root, so it must be opt-in", defaults.replaceSystemBars)
    }

    @Test
    fun defaultsDoNotChangeTheCarWithoutBeingAsked() {
        val defaults = LauncherSettings()

        // Each of these starts a behaviour the driver did not ask for — dimming at dusk, or the
        // launcher speaking out loud. An update that flipped one would be a bug however defensible.
        assertFalse(defaults.clockFallback)
        assertFalse(defaults.readNowPlaying)
        assertFalse(defaults.readNotifications)
        assertEquals(DayNightMode.AUTO, defaults.dayNightMode)
        assertEquals(DriverSideMode.AUTO, defaults.driverSideMode)
    }

    @Test
    fun everyHomeWidgetStartsVisible() {
        val defaults = LauncherSettings()

        assertTrue(defaults.showMedia)
        assertTrue(defaults.showRadio)
        assertTrue(defaults.showClimate)
        assertTrue(defaults.showNav)
        assertTrue(defaults.shadeEnabled)
    }

    @Test
    fun gridColumnBoundsBracketTheDefault() {
        // SettingsStore.setGridColumns coerces into [MIN, MAX]. A default outside that range would
        // be unreachable again once the driver touched the slider.
        assertTrue(LauncherSettings.MIN_GRID_COLUMNS >= 1)
        assertTrue(LauncherSettings.MIN_GRID_COLUMNS <= LauncherSettings.DEFAULT_GRID_COLUMNS)
        assertTrue(LauncherSettings.DEFAULT_GRID_COLUMNS <= LauncherSettings.MAX_GRID_COLUMNS)
        assertEquals(LauncherSettings.DEFAULT_GRID_COLUMNS, LauncherSettings().gridColumns)
    }

    @Test
    fun nightHoursSitInsideTheClamp() {
        // setNightStartHour / setNightEndHour clamp to [MIN_HOUR, MAX_HOUR]; the defaults must be
        // reachable values, and the window must be an evening-to-morning one (start after end).
        assertEquals(0, LauncherSettings.MIN_HOUR)
        assertEquals(23, LauncherSettings.MAX_HOUR)

        val defaults = LauncherSettings()
        listOf(defaults.nightStartHour, defaults.nightEndHour).forEach { hour ->
            assertTrue("$hour is outside the clock", hour in LauncherSettings.MIN_HOUR..LauncherSettings.MAX_HOUR)
        }
        assertTrue(
            "night must start in the evening and end in the morning",
            defaults.nightStartHour > defaults.nightEndHour,
        )
    }

    @Test
    fun dayNightModeNamesSurvivePersistence() {
        // Persisted by name and read back with valueOf, with AUTO as the catch. Renaming or
        // reordering an entry silently resets every unit's stored choice to AUTO.
        assertEquals(
            listOf("AUTO", "FORCE_DAY", "FORCE_NIGHT", "CLOCK"),
            DayNightMode.values().map { it.name },
        )
    }

    @Test
    fun placementNamesSurvivePersistence() {
        // AppDirectoryStore serialises these as the second half of a "pkg|PLACEMENT" token and
        // drops any token whose enum name no longer resolves — i.e. renaming one wipes the
        // driver's app-directory overrides with no error anywhere.
        assertEquals(
            listOf("HOME", "SYSTEM", "HIDDEN"),
            Placement.values().map { it.name },
        )
    }

    @Test
    fun placementNamesCarryNoSeparator() {
        // The token format is "pkg|PLACEMENT", split on the LAST '|'. An enum name containing the
        // separator would decode into a package name with a stray suffix.
        Placement.values().forEach { placement ->
            assertFalse(placement.name.contains('|'))
        }
    }
}
