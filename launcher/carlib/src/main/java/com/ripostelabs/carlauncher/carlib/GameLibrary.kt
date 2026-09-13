package com.ripostelabs.carlauncher.carlib

/**
 * GameLibrary — the ROMs on the unit, grouped by system, each paired with the libretro core that
 * runs it.
 *
 * RetroArch does not scan for content by itself; without this the driver would be digging through
 * its file browser on a 1920x720 panel with a wheel button. Instead the launcher owns a plain
 * directory tree, `roms/<system>/<Title>.<ext>`, and starts RetroArch with the ROM and the core
 * already chosen. The mapping lives here so it is testable without a device.
 *
 *   roms/
 *   ├── nes/Nova the Squirrel.nes     → fceumm
 *   ├── gbc/uCity.gbc                 → gambatte
 *   └── genesis/Cave Story MD.gen     → genesis_plus_gx
 *
 * Matching is by directory AND extension: a `.sha256` next to a ROM, or a ROM dropped in the wrong
 * folder, is skipped rather than launched into a core that will reject it.
 */
data class GameSystem(
    val dir: String,
    val label: String,
    /** libretro core name, without the `_libretro_android.so` suffix. */
    val core: String,
    val extensions: Set<String>,
)

data class Rom(
    val title: String,
    /** `<system dir>/<file>`, relative to the ROM root. */
    val relativePath: String,
    val system: GameSystem,
)

object GameLibrary {

    /**
     * Systems the library knows. Short on purpose, like [GameApps.KNOWN]: each entry names a core
     * that has to be downloaded inside RetroArch before it can run, and a long list would promise
     * cores nobody has fetched on this unit.
     */
    val SYSTEMS: List<GameSystem> = listOf(
        GameSystem("nes", "NES", "fceumm", setOf("nes")),
        GameSystem("snes", "Super NES", "snes9x", setOf("sfc", "smc")),
        GameSystem("gb", "Game Boy", "gambatte", setOf("gb")),
        GameSystem("gbc", "Game Boy Color", "gambatte", setOf("gbc", "gb")),
        GameSystem("gba", "Game Boy Advance", "mgba", setOf("gba")),
        GameSystem("genesis", "Mega Drive", "genesis_plus_gx", setOf("gen", "md", "bin")),
    )

    /**
     * The ROMs among [files] (paths relative to the ROM root), in [SYSTEMS] order then by title.
     *
     * Order is the catalogue's, not the filesystem's, so the list does not reshuffle when a file
     * is added.
     */
    fun catalogue(files: Collection<String>): List<Rom> {
        val rank = SYSTEMS.withIndex().associate { it.value.dir to it.index }

        return files.mapNotNull(::parse)
            .sortedWith(compareBy({ rank.getValue(it.system.dir) }, { it.title.lowercase() }))
    }

    /** Where RetroArch keeps a downloaded core: its private data dir, not the sdcard. */
    fun corePath(frontendPackage: String, system: GameSystem): String =
        "/data/data/$frontendPackage/cores/${system.core}_libretro_android.so"

    private fun parse(relativePath: String): Rom? {
        val parts = relativePath.split("/")
        if (parts.size != 2) {
            return null
        }

        val (dir, file) = parts
        val system = SYSTEMS.firstOrNull { it.dir == dir } ?: return null
        val extension = file.substringAfterLast(".", "").lowercase()
        if (extension !in system.extensions) {
            return null
        }

        return Rom(title = file.substringBeforeLast("."), relativePath = relativePath, system = system)
    }
}
