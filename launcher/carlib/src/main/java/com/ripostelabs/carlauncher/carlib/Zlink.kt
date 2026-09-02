package com.ripostelabs.carlauncher.carlib

/**
 * The Zlink phone-projection receiver (`com.zjinnova.zlink`: CarPlay, Android Auto, HiCar,
 * mirror, DLNA) as the vendor gateway drives it (`eventcenter/manager/ZlinkManage.java`).
 *
 * ⚠ UNVERIFIED on the car: the receiver's own DEX is packed, so everything here is the
 * gateway's side of the bridge plus the manifest. The gateway sends all of it unpermissioned
 * (`sendBroadcastAsUser(ALL)`, `:608-613`), so a normal app can send the same.
 *
 * ```
 *  launcher ──▶ ZLINK_MAIN (page, feature)            opens the Zlink main screen   :476-483
 *  launcher ──▶ <protocol activity by SysVar>          opens the live projection     :486-505
 *  launcher ──▶ com.zjinnova.zlink command=REQ_SPEC_FUNC_CMD specFuncCode=<n>       :608-613
 *  zlink    ──▶ com.zjinnova.zlink status / command / phoneMode / phoneType         :140-150
 *  gateway  ──▶ ACTION_CARPLAY_TELEPHONE_STATUS_EVENT int (mic polled every 300 ms) :516-536
 * ```
 *
 * NEVER put [CARPLAY_ACTIVITY] (or the other protocol activities) or `com.szchoiceway.btsuite`
 * into SysVar `SYS_LAUNCHER_APP_HIDE_KEY`: the gateway rewrites `rw.zlink.disable.features`
 * from that string and kills Zlink (`ZlinkManage.java:568-584`). See [SysVar.KEY_LAUNCHER_APP_HIDE].
 */
object Zlink {

    const val PACKAGE = "com.zjinnova.zlink"

    // ---- Deep link into the main screen (manifest: features.main.MainActivity) ----
    const val ACTION_MAIN = "zjinnova.android.intent.action.ZLINK_MAIN"
    const val EXTRA_PAGE = "page"
    const val EXTRA_FEATURE = "feature"

    // ---- Protocol activities (`EventUtils.*_MODE_CONNECTED_CLASS_NAME`) ----
    const val CARPLAY_ACTIVITY = "com.zjinnova.android.zlink.features.launcher.CarPlayActivity"
    const val ANDROID_AUTO_ACTIVITY = "com.zjinnova.android.zlink.features.launcher.AutoActivity"
    const val HICAR_ACTIVITY = "com.zjinnova.android.zlink.features.launcher.HiCarActivity"
    const val MIRROR_ACTIVITY = "com.zjinnova.android.zlink.features.launcher.MirrorActivity"
    const val DLNA_ACTIVITY = "com.zjinnova.android.zlink.features.dlna.DlnaActivity"

    /** SysVar key holding the last connected protocol (`SysProviderOpt.java:482`). */
    const val KEY_PHONELINK_TYPE = "Sys_Zxw_Zj_Phonelink_Type_Key"

    // ---- The two-way message broadcast ----
    const val ACTION_MESSAGE = "com.zjinnova.zlink"
    const val EXTRA_STATUS = "status"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_PHONE_MODE = "phoneMode"
    const val EXTRA_PHONE_TYPE = "phoneType"

    /** `status` values the gateway handles (`ZlinkManage.java:205-300`). */
    const val STATUS_CONNECTED = "CONNECTED"
    const val STATUS_DISCONNECT = "DISCONNECT"
    const val STATUS_MAIN_PAGE_SHOW = "MAIN_PAGE_SHOW"
    const val STATUS_MAIN_PAGE_HIDDEN = "MAIN_PAGE_HIDDEN"
    const val STATUS_EXIT = "EXIT"
    const val STATUS_PHONE_CALL_ON = "PHONE_CALL_ON"
    const val STATUS_PHONE_CALL_OFF = "PHONE_CALL_OFF"
    /** Projection audio started / stopped (`ZlinkManage.java:591-605`, the now-playing path). */
    const val STATUS_MAIN_AUDIO_START = "MAIN_AUDIO_START"
    const val STATUS_MAIN_AUDIO_STOP = "MAIN_AUDIO_STOP"

    /**
     * ⚠ UNVERIFIED: `page` / `feature` values for [ACTION_MAIN]. Nothing in the decompiled
     * estate ever calls `startZlinkMainActivity`, so these are the plainest guess; the
     * manifest routes the action to `features.main.MainActivity` regardless of the extras.
     */
    const val PAGE_MAIN = "main"
    const val FEATURE_CARPLAY = "carplay"

    const val COMMAND_SPEC_FUNC = "REQ_SPEC_FUNC_CMD"
    const val EXTRA_SPEC_FUNC_CODE = "specFuncCode"

    /** Gateway-side call state, derived from the mic (`EventUtils.java:53-54,2666-2667`). */
    const val ACTION_TELEPHONE_STATUS =
        "com.szchoiceway.eventcenter.EventUtils.ACTION_CARPLAY_TELEPHONE_STATUS_EVENT"
    const val EXTRA_TELEPHONE_STATUS = "EventUtils.ACTION_CARPLAY_TELEPHONE_STATUS_DATA"

    /** `ZlinkManage.KEYCODE_*` (`:35-42`) — what a `REQ_SPEC_FUNC_CMD` can ask for. */
    enum class Feature(val code: Int) {
        SIRI(1500),
        MAPS(1504),
        PHONE(1505),
        MUSIC(1506),
        NOW_PLAYING(1507),
        HOME(1508),
    }

    /** `ZlinkManage.startZlinkMainActivity`. Page/feature vocabularies are UNVERIFIED. */
    fun mainPage(page: String, feature: String): IntentSpec = IntentSpec(
        action = ACTION_MAIN,
        packageName = PACKAGE,
        strings = mapOf(EXTRA_PAGE to page, EXTRA_FEATURE to feature),
    )

    /** The launcher's CarPlay deep link (RAV4-52): [mainPage] with the [PAGE_MAIN] guess. */
    fun open(): IntentSpec = mainPage(PAGE_MAIN, FEATURE_CARPLAY)

    /**
     * `ZlinkManage.sendKeyCodeToCarplay`. ⚠ UNVERIFIED whether Zlink 5.4.62 honours any code
     * from a sender other than the gateway; the broadcast is unpermissioned on both sides.
     */
    fun request(feature: Feature): IntentSpec = IntentSpec(
        action = ACTION_MESSAGE,
        strings = mapOf(EXTRA_COMMAND to COMMAND_SPEC_FUNC),
        ints = mapOf(EXTRA_SPEC_FUNC_CODE to feature.code),
    )

    /**
     * The projection activity for a [KEY_PHONELINK_TYPE] value, chosen exactly as
     * `ZlinkManage.startZlinkActivity` does: HiCar / Auto / mirror / DLNA by prefix, CarPlay
     * for everything else (including an unset key).
     */
    fun projectionActivity(phoneLinkType: String?): String {
        val type = phoneLinkType.orEmpty()
        return when {
            type.startsWith("hicar_") -> HICAR_ACTIVITY
            type.startsWith("auto_") -> ANDROID_AUTO_ACTIVITY
            type.startsWith("airplay_") || type.startsWith("android_mirror_") -> MIRROR_ACTIVITY
            type.startsWith("dlna_") -> DLNA_ACTIVITY
            else -> CARPLAY_ACTIVITY
        }
    }
}
