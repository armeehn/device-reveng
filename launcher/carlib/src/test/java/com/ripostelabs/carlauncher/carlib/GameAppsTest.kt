package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure to guard against is reporting the wrong build as present. RetroArch ships several
 * packages with near-identical ids, and on this head unit picking the wrong ABI is the difference
 * between running and crashing on first launch.
 */
class GameAppsTest {

    @Test
    fun `an installed frontend is found`() {
        val found = GameApps.installed(listOf("com.retroarch.aarch64", "com.android.settings"))

        assertEquals(listOf("RetroArch (64-bit)"), found.map { it.label })
    }

    @Test
    fun `several frontends come back in catalogue order`() {
        // Deliberately supplied in the opposite order to the catalogue.
        val found = GameApps.installed(listOf("org.ppsspp.ppsspp", "com.retroarch.aarch64"))

        assertEquals(listOf("RetroArch (64-bit)", "PPSSPP"), found.map { it.label })
    }

    @Test
    fun `the ABI variants are distinct entries`() {
        val ids = GameApps.KNOWN.map { it.packageName }

        assertTrue(ids.contains("com.retroarch"))
        assertTrue(ids.contains("com.retroarch.aarch64"))
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the library plays through RetroArch, whichever build is present`() {
        val installed = GameApps.installed(listOf("org.ppsspp.ppsspp", "com.retroarch.ra32"))

        assertEquals("com.retroarch.ra32", GameApps.libretroFrontend(installed)?.packageName)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the 32-bit build is not reported as the 64-bit one`() {
        val found = GameApps.installed(listOf("com.retroarch"))

        // A substring match would call this the aarch64 build and send someone after the wrong APK.
        assertEquals(listOf("RetroArch"), found.map { it.label })
    }

    @Test
    fun `a package that merely contains a known id does not match`() {
        val found = GameApps.installed(listOf("com.retroarch.clone", "org.ppsspp.ppsspp.beta"))

        assertTrue(found.isEmpty())
    }

    @Test
    fun `a standalone emulator alone gives the library nothing to play through`() {
        val installed = GameApps.installed(listOf("org.ppsspp.ppsspp", "com.dsemu.drastic"))

        assertEquals(null, GameApps.libretroFrontend(installed))
    }

    @Test
    fun `nothing installed reports nothing`() {
        assertTrue(GameApps.installed(emptyList()).isEmpty())
        assertFalse(GameApps.anyInstalled(listOf("com.android.settings")))
    }
}
