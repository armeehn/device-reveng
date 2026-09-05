package com.ripostelabs.carlauncher.carlib

/**
 * The phone-projection session as Zlink reports it, for the status bar and the quick-launch
 * tile (RAV4-52). Two unprotected broadcasts feed it, both decoded by [CarPlayDecode]:
 *
 * ```
 *  zlink   ──▶ com.zjinnova.zlink  status=CONNECTED phoneMode=carplay_wireless  connected
 *  zlink   ──▶ com.zjinnova.zlink  status=MAIN_AUDIO_START | MAIN_AUDIO_STOP     audioPlaying
 *  zlink   ──▶ com.zjinnova.zlink  status=DISCONNECT                             gone
 *  zlink   ──▶ com.zjinnova.zlink  status=PHONE_CALL_ON | PHONE_CALL_OFF         inCall
 *  gateway ──▶ ACTION_CARPLAY_TELEPHONE_STATUS_EVENT int 1 | 0                   inCall
 * ```
 *
 * ⚠ UNVERIFIED on the car: the `status` vocabulary is what the gateway's
 * `handleCarPlayStatus` switches on (`ZlinkManage.java:205-300`), not a capture from
 * Zlink 5.4.62 itself, whose DEX is packed. Consumers must treat [lastEventMs] == 0 as
 * "no signal yet" and show nothing.
 */
data class CarPlayState(
    val connected: Boolean = false,
    /** `phoneMode` of the last CONNECTED (e.g. `carplay_wireless`); null when unknown or gone. */
    val phoneMode: String? = null,
    val inCall: Boolean = false,
    /**
     * MAIN_AUDIO_START seen, no MAIN_AUDIO_STOP since. The gateway turns the pair into its
     * play flag for SRC_CARPLAY (`ZlinkManage.java:296-301`); no track metadata rides with it.
     */
    val audioPlaying: Boolean = false,
    /** Arrival time of the last status of any shape; 0 = none this session. */
    val lastEventMs: Long = 0L,
) {

    /** Transport of the session, read off [phoneMode]'s `_wired` / `_wireless` suffix. */
    enum class Link { WIRED, WIRELESS }

    val link: Link?
        get() = when (phoneMode?.substringAfterLast(MODE_SEPARATOR, missingDelimiterValue = "")) {
            SUFFIX_WIRED -> Link.WIRED
            SUFFIX_WIRELESS -> Link.WIRELESS
            else -> null
        }

    private companion object {
        const val MODE_SEPARATOR = '_'
        const val SUFFIX_WIRED = "wired"
        const val SUFFIX_WIRELESS = "wireless"
    }
}

/** Pure decoder for the two broadcasts behind [CarPlayState]. */
internal object CarPlayDecode {

    /** `sendCarPlayPhoneStatusBroadcastEvt(ctx, 1)` when the mic is live (`ZlinkManage.java:552`). */
    const val TELEPHONE_IN_CALL = 1
    const val TELEPHONE_IDLE = 0

    /** Fold one `com.zjinnova.zlink` broadcast that carries a `status` extra into [prev]. */
    fun applyStatus(prev: CarPlayState, status: String, phoneMode: String?, atMs: Long): CarPlayState {
        val stamped = prev.copy(lastEventMs = atMs)

        return when (status) {
            // CONNECTED is the only status that names the protocol; a blank one keeps the old.
            Zlink.STATUS_CONNECTED -> stamped.copy(
                connected = true,
                phoneMode = phoneMode?.takeIf { it.isNotBlank() } ?: prev.phoneMode,
            )
            // Audio can only start on a live session, so it counts as connected even when the
            // CONNECTED broadcast was missed (launcher started after the phone was plugged in).
            Zlink.STATUS_MAIN_AUDIO_START -> stamped.copy(connected = true, audioPlaying = true)
            Zlink.STATUS_MAIN_AUDIO_STOP -> stamped.copy(audioPlaying = false)
            // EXIT only leaves the source mode (`:228-230`); the phone stays connected.
            Zlink.STATUS_DISCONNECT -> stamped.copy(
                connected = false,
                phoneMode = null,
                inCall = false,
                audioPlaying = false,
            )
            Zlink.STATUS_PHONE_CALL_ON -> stamped.copy(connected = true, inCall = true)
            Zlink.STATUS_PHONE_CALL_OFF -> stamped.copy(inCall = false)
            else -> stamped
        }
    }

    /** Fold one gateway telephone-status event (mic polled every 300 ms) into [prev]. */
    fun applyTelephone(prev: CarPlayState, status: Int, atMs: Long): CarPlayState = when (status) {
        TELEPHONE_IN_CALL -> prev.copy(inCall = true, lastEventMs = atMs)
        TELEPHONE_IDLE -> prev.copy(inCall = false, lastEventMs = atMs)
        else -> prev
    }
}
