package com.ripostelabs.carlauncher.carlib

/**
 * GameApps — which emulator frontends are on the unit, and what to call them.
 *
 * The catalogue lives here rather than in the screen so "is RetroArch installed" is answerable
 * without a device. Matching is exact on the package id: emulator projects ship several builds
 * under near-identical names, and a substring match would happily report the 32-bit build as the
 * 64-bit one — which on this head unit is the difference between running and not.
 *
 * ── Why the ABI variants are listed separately ──────────────────────────────────────────────────
 * RetroArch publishes `com.retroarch` (32-bit), `com.retroarch.aarch64` and `com.retroarch.ra32`.
 * They are genuinely different installs and can coexist. Reporting the specific one that is
 * present is the point: picking the wrong APK is the most common way this ends in a silent crash
 * on first launch, and the unit's own ABI has not been confirmed yet.
 */
data class GameApp(
    val packageName: String,
    val label: String,
)

object GameApps {

    /**
     * Frontends worth offering. Deliberately short and specific — a long speculative list would
     * make the screen claim support for things nobody has run on this hardware.
     */
    val KNOWN: List<GameApp> = listOf(
        GameApp("com.retroarch.aarch64", "RetroArch (64-bit)"),
        GameApp("com.retroarch", "RetroArch"),
        GameApp("com.retroarch.ra32", "RetroArch (32-bit)"),
        GameApp("org.ppsspp.ppsspp", "PPSSPP"),
        GameApp("com.dsemu.drastic", "DraStic"),
        GameApp("me.magnum.melonds", "melonDS"),
        GameApp("org.mupen64plusae.v3.alpha", "Mupen64Plus"),
        GameApp("com.explusalpha.Snes9xPlus", "Snes9x EX+"),
    )

    /**
     * Those of [KNOWN] present in [installed], in catalogue order.
     *
     * Order is the catalogue's, not the system's, so the list a driver sees does not reshuffle
     * itself when an unrelated app is installed.
     */
    fun installed(installed: Collection<String>): List<GameApp> {
        val present = installed.toSet()
        return KNOWN.filter { it.packageName in present }
    }

    /** Whether anything at all is available to launch. */
    fun anyInstalled(installed: Collection<String>): Boolean = installed(installed).isNotEmpty()
}
