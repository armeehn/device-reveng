package com.ripostelabs.carlauncher.media

/**
 * Friendly names for media sources whose app label is not what the driver would call them.
 *
 * The ZLink receiver publishes the CarPlay session under its own label, "zlink5", so the
 * now-playing card read as a vendor app rather than the phone. Membership is explicit: a
 * prefix match on `com.zjinnova.` would also rename the receiver's helper packages.
 */
object SourceLabels {
    private const val ZLINK = "com.zjinnova.zlink"
    private const val CARPLAY = "CarPlay"

    private val byPackage = mapOf(ZLINK to CARPLAY)

    /** The friendly label for [pkg], or null to fall back to the package's own label. */
    fun of(pkg: String): String? = byPackage[pkg]
}
