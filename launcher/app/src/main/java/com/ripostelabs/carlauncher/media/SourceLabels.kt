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

    /**
     * RAV4-52: what the gateway titles the source while a phone is projected
     * (`ZlinkManage.setCarPlayValidModeInfor`, `:591-605`). Its `getValidModeTitle` answer.
     */
    private val projectionTitles = setOf("Carplay", "Android Auto", "HUAWEI HiCar", "Airplay", "DLNA")

    /** The friendly label for [pkg], or null to fall back to the package's own label. */
    fun of(pkg: String): String? = byPackage[pkg]

    /** The session belongs to the Zlink receiver. */
    fun isCarPlay(pkg: String?): Boolean = pkg == ZLINK

    /** The vendor's current source title names a projected phone. */
    fun isProjection(vendorTitle: String?): Boolean = vendorTitle in projectionTitles
}
