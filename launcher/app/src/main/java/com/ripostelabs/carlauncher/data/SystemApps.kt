package com.ripostelabs.carlauncher.data

import android.content.pm.ApplicationInfo

/**
 * Which drawer entries fold into the System folder.
 *
 * The home grid stays clear of vendor and engineering tools (TestTools, CanbusDebug,
 * ApkInstall, the atslcarconsole shell, AOSP samples, …). The decision is data-driven via
 * [alwaysShow] / [alwaysHidePrefixes]; the raw FLAG_SYSTEM bit is the fallback.
 */
object SystemApps {

    /** Curated launchers that ARE system apps but should always stay on the home grid. */
    private val alwaysShow = setOf(
        "com.android.vending",              // Play Store
        "com.google.android.apps.maps",     // Maps
        "com.android.settings",             // Settings
        "com.topjohnwu.magisk",             // Magisk
        "com.android.chrome",
        "com.google.android.projection.gearhead", // Android Auto
        "com.google.android.googlequicksearchbox",
        "org.codeaurora.snapcam",           // camera
        // Zlink phone-projection receiver. It keeps exactly one launcher alias enabled for
        // whichever protocol is configured (features.launcher.CarPlayActivity today; the
        // AutoActivity/HiCarActivity/... aliases when the unit is switched), so this surfaces
        // a single "CarPlay" tile on the main grid rather than the whole vendor suite.
        "com.zjinnova.zlink",
    )

    /** Package prefixes to always push into the System folder regardless of flags. */
    private val alwaysHidePrefixes = listOf(
        "com.szchoiceway.",
        "com.choiceway.",
        "com.lfg.szchoiceway.",
        "com.zjinnova.",                    // zlink internals (com.zjinnova.zlink itself is alwaysShow)
        "com.ivicar.",
        "com.syu.",
        "com.android.atslcarconsole",       // vendor console shell
        "com.example.android.",             // AOSP sample leftovers
        "com.mmbox.",
    )

    private const val SYSTEM_FLAGS =
        ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP

    /**
     * True when [packageName] with ApplicationInfo [flags] belongs in the System folder.
     * Suite members carry FLAG_SYSTEM on Riposte OS (installed under /product) yet are the
     * product, so they stay on the home grid.
     */
    fun isSystem(packageName: String, flags: Int): Boolean {
        if (packageName in alwaysShow || RiposteSuite.isSuiteApp(packageName)) {
            return false
        }
        if (alwaysHidePrefixes.any { packageName.startsWith(it) }) {
            return true
        }
        return flags and SYSTEM_FLAGS != 0
    }
}
