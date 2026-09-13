package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure to guard against is handing RetroArch a file its core cannot load: a checksum
 * sidecar, a ROM in the wrong folder, or a folder the library does not know. Each of those must
 * vanish from the list rather than appear as a playable title.
 */
class GameLibraryTest {

    @Test
    fun `a rom is titled from its file name and paired with its core`() {
        val roms = GameLibrary.catalogue(listOf("nes/Nova the Squirrel.nes"))

        assertEquals(1, roms.size)
        assertEquals("Nova the Squirrel", roms[0].title)
        assertEquals("fceumm", roms[0].system.core)
    }

    @Test
    fun `systems come back in catalogue order then by title`() {
        // Supplied out of order on both axes.
        val roms = GameLibrary.catalogue(
            listOf("genesis/Cave Story MD.gen", "nes/nova.nes", "gbc/uCity.gbc", "nes/Alter Ego.nes"),
        )

        assertEquals(listOf("Alter Ego", "nova", "uCity", "Cave Story MD"), roms.map { it.title })
    }

    @Test
    fun `extension matching ignores case`() {
        assertEquals(1, GameLibrary.catalogue(listOf("gba/Celeste Classic.GBA")).size)
    }

    @Test
    fun `the core path is inside the frontend's private dir`() {
        val nes = GameLibrary.SYSTEMS.first { it.dir == "nes" }

        assertEquals(
            "/data/data/com.retroarch.aarch64/cores/fceumm_libretro_android.so",
            GameLibrary.corePath("com.retroarch.aarch64", nes),
        )
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a checksum sidecar is not a rom`() {
        assertTrue(GameLibrary.catalogue(listOf("nes/Nova the Squirrel.nes.sha256")).isEmpty())
    }

    @Test
    fun `a rom in the wrong folder is skipped`() {
        // gambatte would refuse a NES image; better absent than a launch that dies.
        assertTrue(GameLibrary.catalogue(listOf("gbc/Nova the Squirrel.nes")).isEmpty())
    }

    @Test
    fun `unknown folders, loose files and nested paths are skipped`() {
        val roms = GameLibrary.catalogue(
            listOf("psx/game.bin", "SOURCES.md", "nes/sub/game.nes", "nes/README"),
        )

        assertTrue(roms.isEmpty())
    }
}
