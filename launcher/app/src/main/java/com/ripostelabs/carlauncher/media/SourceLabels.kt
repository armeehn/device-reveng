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
    /** Riposte OS 0.2: the projection suite answers the OEM daemon and holds the session. */
    private const val PROJECTION = "com.ripostelabs.projection"
    private const val CARPLAY = "CarPlay"
    /**
     * The Bluetooth stack's AVRCP session (BluetoothMediaBrowserService). With a wireless
     * CarPlay phone the audio rides Wi-Fi, but the track title and play state still arrive
     * over AVRCP, so this is the CarPlay track whenever a phone is projected.
     */
    private const val BLUETOOTH_STACK = "com.android.bluetooth"

    private val byPackage = mapOf(ZLINK to CARPLAY, PROJECTION to CARPLAY)

    /**
     * RAV4-52: what the gateway titles the source while a phone is projected
     * (`ZlinkManage.setCarPlayValidModeInfor`, `:591-605`). Its `getValidModeTitle` answer.
     */
    private val projectionTitles = setOf("Carplay", "Android Auto", "HUAWEI HiCar", "Airplay", "DLNA")

    /** The friendly label for [pkg], or null to fall back to the package's own label. */
    fun of(pkg: String): String? = byPackage[pkg]

    /** The session belongs to a CarPlay receiver: the OEM app on stock, ours on 0.2. */
    fun isCarPlay(pkg: String?): Boolean = pkg == ZLINK || pkg == PROJECTION

    /** The track is CarPlay's: a receiver's own session, or the stack's AVRCP one while projected. */
    fun isCarPlay(pkg: String?, projected: Boolean): Boolean =
        isCarPlay(pkg) || (projected && pkg == BLUETOOTH_STACK)

    /** Relabel the stack's AVRCP session as CarPlay while a phone is projected. */
    fun viaCarPlay(now: NowPlaying, projected: Boolean): NowPlaying =
        if (projected && now.sourcePackage == BLUETOOTH_STACK) now.copy(sourceLabel = CARPLAY) else now

    /** The vendor's current source title names a projected phone. */
    fun isProjection(vendorTitle: String?): Boolean = vendorTitle in projectionTitles
}
