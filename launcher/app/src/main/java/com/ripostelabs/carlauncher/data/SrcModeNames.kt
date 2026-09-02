package com.ripostelabs.carlauncher.data

/**
 * Names for the gateway's source mode, `EventUtils.eSrcMode` (decompiled
 * `com.szchoiceway.eventcenter/EventUtils.java:2005-2069`). `getValidMode()` returns the int;
 * this turns it back into the enum constant so a Setup Doctor row reads "RADIO (1)" rather
 * than "1". Modes at or below [AUDIO_SOURCE_MAX] (SRC_AUX) are the ones the MCU treats as an
 * audio source and `sendMode` may run `kill3rdAPK` for.
 */
object SrcModeNames {

    const val AUDIO_SOURCE_MAX = 40
    const val UNKNOWN = "unknown"
    const val UNBOUND = "gateway not bound"

    private val NAMES: Map<Int, String> = mapOf(
        0 to "NONE", 1 to "RADIO", 2 to "DVD", 3 to "USB", 4 to "CARD", 5 to "IPOD",
        6 to "BT", 7 to "BTMUSIC", 8 to "CMMB", 9 to "TV", 10 to "MOVIE", 11 to "MUSIC",
        12 to "EBOOK", 13 to "IMAGE", 14 to "ANDROID", 15 to "VMCD", 16 to "NETWORK",
        17 to "CARMEDIA", 18 to "CAR_BT", 19 to "HDMI", 30 to "CARCONSOLE", 31 to "PHONELINK",
        32 to "CARPLAY", 34 to "DAB", 39 to "SXM", 40 to "AUX", 41 to "BACKCAR", 42 to "GPS",
        43 to "HOME", 44 to "REHOME", 45 to "COMPASS", 46 to "STANDBY", 47 to "EQ",
        48 to "BACKLIGHT_SET", 49 to "SETUP", 50 to "FCAM", 51 to "RCAM", 52 to "BCAM",
        53 to "DVR", 60 to "CUSTOMIZE", 61 to "CUSTOMIZE1", 62 to "CUSTOMIZE2",
        63 to "CUSTOMIZE3", 64 to "CUSTOMIZE4", 65 to "CUSTOMIZE5", 66 to "CUSTOMIZE6",
        80 to "MCU_VERSION", 81 to "TW8823_VERSION", 99 to "NULL", 100 to "POWERON",
        101 to "POWEROFF", 102 to "MIX_GPS", 103 to "IDLE_MODE", 104 to "IDLE_MODE_RELEASE",
        105 to "DONGHUA_END", 150 to "CARAIR", 152 to "BT_ECAR", 153 to "EXPLORER",
        154 to "APPLIST", 155 to "CAR_AUX", 156 to "FILE_MANAGER", 157 to "BT_ONLY",
        158 to "PIC", 159 to "MORESETTING",
    )

    /** The enum constant without its `SRC_` prefix, or [UNKNOWN]. */
    fun name(mode: Int): String = NAMES[mode] ?: UNKNOWN

    /** "RADIO (1)" for a display row; [UNBOUND] when the gateway gave no answer. */
    fun label(mode: Int?): String {
        if (mode == null) {
            return UNBOUND
        }
        return "${name(mode)} ($mode)"
    }

    fun isAudioSource(mode: Int): Boolean = mode <= AUDIO_SOURCE_MAX
}
