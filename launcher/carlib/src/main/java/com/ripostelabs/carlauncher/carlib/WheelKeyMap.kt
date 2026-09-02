package com.ripostelabs.carlauncher.carlib

import org.json.JSONException
import org.json.JSONObject

/**
 * What a learned resistive steering-wheel key MEANS.
 *
 * The MCU does not know functions, only slots. The vendor learn app
 * (`com.szchoiceway.learn.key`, `view/CarWheelView.java`) assigns each chosen function the
 * lowest free slot 0..14 (`:78-86`), teaches the MCU that slot, and persists the result as a
 * JSON object in SysVar `wheel_key_learn_custom` (`:293`,
 * `zxw/lib/ui/util/SystemPropertiesHelps.java:733-734`):
 *
 * ```
 *  {"svg_wheel_next_c":"1","svg_wheel_pre_c":"2","svg_wheel_mode_home":"0"}
 *      icon id  ─────────┘       slot as a STRING ┘
 * ```
 *
 * Icon ids are the field names of the gateway's `base/WheelCustomKey.java`; their meaning is
 * fixed by `manager/McuToArmDataManage.onReadMcuPanelCustomKey` (`:441-520`), transcribed into
 * [WheelFunction]. Panel keys use the same shape under `mcu_panel_key_learn_custom`.
 *
 * Learn protocol, for reference (all through `IEventService.sendWheelKey(n)`, MCU frame
 * `{0x07, n}`; `CarWheelView.java:315-378`): 112 enter learn mode, `<slot>` learn the next
 * press into that slot, 114 save, 113 exit, 115 clear all, 116 high-impedance wheel,
 * 117 low-impedance wheel (also toggles bit 2 of SysVar `Sys_McuSet`). The MCU answers with
 * `STEER_WHEEL_INFOR` (LPARAM != 0 read as success, `:176-183`) and [STEER_WHEEL_STATUS]
 * carrying [EXTRA_STUDY_STATUS], a 16-bit mask of learned slots. Not exposed as UI here.
 */
class WheelKeyMap private constructor(private val bySlot: Map<Int, WheelFunction>) {

    val isEmpty: Boolean get() = bySlot.isEmpty()

    /** All learned pairs, ascending by slot. */
    val entries: List<Pair<Int, WheelFunction>>
        get() = bySlot.entries.sortedBy { it.key }.map { it.key to it.value }

    fun functionOf(slot: Int): WheelFunction? = bySlot[slot]

    fun slotOf(function: WheelFunction): Int? =
        bySlot.entries.firstOrNull { it.value == function }?.key

    override fun equals(other: Any?): Boolean = other is WheelKeyMap && other.bySlot == bySlot
    override fun hashCode(): Int = bySlot.hashCode()
    override fun toString(): String = "WheelKeyMap$bySlot"

    companion object {
        const val SLOT_MIN = 0
        const val SLOT_MAX = 14

        /** The MCU's learn-mode opcodes, sent through `sendWheelKey` (`CarWheelView.java`). */
        const val LEARN_ENTER = 112
        const val LEARN_EXIT = 113
        const val LEARN_SAVE = 114
        const val LEARN_CLEAR = 115
        const val LEARN_HIGH_IMPEDANCE = 116
        const val LEARN_LOW_IMPEDANCE = 117

        /** Learned-slot mask broadcast (`EventService.java:3065-3066`). */
        const val STEER_WHEEL_STATUS = "com.choiceway.eventcenter.EventUtils.STEER_WHEEL_STATUS"
        const val EXTRA_STUDY_STATUS = "EventUtils.STEER_WHEEL_STUDY_STATUS"

        val EMPTY = WheelKeyMap(emptyMap())

        /**
         * Slot behind a `STEER_WHEEL_INFOR` LPARAM. The gateway sends `bArr[1] + 1`
         * (`EventService.java:2847-2850`), and the learn app teaches slots from 0, so the
         * offset is one. UNVERIFIED: that `bArr[1]` echoes the taught slot is inferred, not
         * observed (the learn app never compares the two, `CarWheelView.java:176-183`).
         */
        fun slotOfLparam(lparam: Int): Int = lparam - 1

        /**
         * Parse the SysVar JSON. Anything unusable — malformed JSON, an icon id we do not
         * know, a non-numeric or out-of-range slot — is skipped, never guessed; a wholly
         * unusable value yields [EMPTY].
         */
        fun parse(json: String?): WheelKeyMap {
            if (json.isNullOrBlank()) {
                return EMPTY
            }
            val obj = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return EMPTY
            }

            val bySlot = LinkedHashMap<Int, WheelFunction>()
            for (iconId in obj.keys()) {
                val function = WheelFunction.byIconId(iconId) ?: continue
                val slot = obj.optString(iconId).trim().toIntOrNull() ?: continue
                if (slot !in SLOT_MIN..SLOT_MAX) {
                    continue
                }
                bySlot[slot] = function
            }
            return if (bySlot.isEmpty()) EMPTY else WheelKeyMap(bySlot)
        }
    }
}

/**
 * The functions the vendor learn app offers, keyed by the icon id it persists.
 * [gatewayName] is the string `McuToArmDataManage` maps the same id to, kept so a log line
 * from the gateway can be matched to one of these.
 */
enum class WheelFunction(val iconId: String, val gatewayName: String) {
    MODE("svg_wheel_mode_c", "Mode"),
    NEXT("svg_wheel_next_c", "Next"),
    PREV("svg_wheel_pre_c", "Prev"),
    POWER("svg_wheel_gj", "Power"),
    NAVI("svg_wheel_dh", "Navi"),
    MUTE("svg_wheel_jy", "Mute"),
    HANG_UP("svg_wheel_gd", "Hangup"),
    TALK("svg_wheel_jt", "Talk"),
    VOLUME_UP("svg_wheel_s_add", "VolAdd"),
    VOLUME_DOWN("svg_wheel_s_j", "VolSub"),
    VOICE("svg_wheel_mode_voice", "Voice"),
    CAMERA_360("svg_wheel_mode_360", "Camera"),
    FM("svg_wheel_mode_fm", "Fm"),
    BACK("svg_wheel_mode_back", "Back"),
    HOME("svg_wheel_mode_home", "Home"),
    OK("svg_wheel_mode_ok", "Ok"),
    VIDEO("svg_wheel_mode_video", "Video"),
    MUSIC("svg_wheel_mode_music", "Music"),
    BACKLIGHT("svg_wheel_mode_backlight_brightness", "BackLight"),
    CAR_INFO("svg_wheel_mode_original_car_info", "Info"),
    AUDIO_DSP("svg_wheel_mode_audio_dsp", "Audio"),
    SETTINGS("svg_wheel_mode_settings", "Setup"),
    CAR_ANDROID("svg_wheel_mode_car_android", "CarAndroid"),
    SPLIT_SCREEN("svg_wheel_mode_fenping", "SplitScreen"),
    RECENT_TASKS("svg_wheel_mode_houtai", "RecentTask"),
    AUX("svg_wheel_mode_aux", "AUX"),
    AMS("svg_wheel_mode_ams", "AMS"),
    APS("svg_wheel_mode_aps", "APS"),
    LOUD("svg_wheel_mode_loud", "Loud"),
    EXTERIOR_CAMERA("svg_wheel_mode_chewaijiankong", "CheWaiJianKong");

    companion object {
        private val byIconId = values().associateBy { it.iconId }

        fun byIconId(iconId: String): WheelFunction? = byIconId[iconId]
    }
}
