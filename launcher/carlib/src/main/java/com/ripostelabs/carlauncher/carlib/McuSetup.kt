package com.ripostelabs.carlauncher.carlib

/**
 * McuSetup — the MCU setup table a driver can change, with the vendor's defaults.
 *
 * eventcenter kept each of these as a SysVar row and re-sent the MCU frames at every boot
 * (`initSysEventState`, EventService.java:3794-3800). On Riposte OS 0.2 the rows live in
 * [McuSetupStore] and the frames come from [McuSetupProtocol]; the row names are kept so a
 * SysVar export reads the same as stock.
 *
 * Defaults are `initSoundValue` / `setRecordDefaultValue` (EventService.java:6507-6557,
 * 6708-6720): balance, fader and the three tone bands centre on 7 of 0..14, EQ 0, loudness
 * off, subwoofer 10, key beep OFF ("0", :6515), sleep option 2, nav volume 28, host default
 * volume 30 (:9929) and every source gain 28 (:6548-6557).
 */
data class McuSetup(
    val balance: Int = CENTRE,
    val fader: Int = CENTRE,
    val tone: Tone = Tone(),
    val eqMode: Int = 0,
    val loudness: Boolean = false,
    val subwoofer: Int = DEFAULT_SUBWOOFER,
    val keyBeep: Beep = Beep.OFF,
    /** The vendor's 1/2/3 option, not minutes; see [McuSetupProtocol.sleepTime]. */
    val sleepTime: Int = DEFAULT_SLEEP_TIME,
    val navVolume: Int = DEFAULT_NAV_VOLUME,
    val hostDefaultVolume: Int = DEFAULT_HOST_VOLUME,
    val gains: SourceGains = SourceGains(),
    val dspLoud: Boolean = false,
) {
    /** The key-press beep the MCU plays on a panel touch (`Set_TouchBeep`). */
    enum class Beep { ON, OFF }

    /** Bass / mid / treble levels and the centre-frequency picks `sendAudioValue` carries (:9420). */
    data class Tone(
        val bass: Int = CENTRE,
        val mid: Int = CENTRE,
        val treble: Int = CENTRE,
        val bassFreq: Int = 0,
        val midFreq: Int = 0,
        val trebleFreq: Int = 0,
    )

    /** Per-source gains in the wire order of `sendVolumeGain` (EventService.java:9662). */
    data class SourceGains(
        val radio: Int = DEFAULT_GAIN,
        val music: Int = DEFAULT_GAIN,
        val movie: Int = DEFAULT_GAIN,
        val btCall: Int = DEFAULT_GAIN,
        val btMusic: Int = DEFAULT_GAIN,
        val tv: Int = DEFAULT_GAIN,
        val dvd: Int = DEFAULT_GAIN,
        val aux: Int = DEFAULT_GAIN,
        val usb: Int = DEFAULT_GAIN,
        val other: Int = DEFAULT_GAIN,
    ) {
        fun asList(): List<Int> = listOf(radio, music, movie, btCall, btMusic, tv, dvd, aux, usb, other)
    }

    /** The table as SysVar rows, the vendor's key names and "1"/"0" booleans. */
    fun toRows(): Map<String, String> = mapOf(
        KEY_BALANCE to "$balance",
        KEY_FADER to "$fader",
        KEY_BASS to "${tone.bass}",
        KEY_MID to "${tone.mid}",
        KEY_TREBLE to "${tone.treble}",
        KEY_BASS_FREQ to "${tone.bassFreq}",
        KEY_MID_FREQ to "${tone.midFreq}",
        KEY_TREBLE_FREQ to "${tone.trebleFreq}",
        KEY_EQ_MODE to "$eqMode",
        KEY_LOUDNESS to flag(loudness),
        KEY_SUBWOOFER to "$subwoofer",
        KEY_KEY_BEEP to flag(keyBeep == Beep.ON),
        KEY_SLEEP_TIME to "$sleepTime",
        KEY_NAV_VOLUME to "$navVolume",
        KEY_HOST_VOLUME to "$hostDefaultVolume",
        KEY_GAIN_RADIO to "${gains.radio}",
        KEY_GAIN_MUSIC to "${gains.music}",
        KEY_GAIN_MOVIE to "${gains.movie}",
        KEY_GAIN_BT_CALL to "${gains.btCall}",
        KEY_GAIN_BT_MUSIC to "${gains.btMusic}",
        KEY_GAIN_TV to "${gains.tv}",
        KEY_GAIN_DVD to "${gains.dvd}",
        KEY_GAIN_AUX to "${gains.aux}",
        KEY_GAIN_USB to "${gains.usb}",
        KEY_GAIN_OTHER to "${gains.other}",
        KEY_DSP_LOUD to flag(dspLoud),
    )

    companion object {
        /** Balance, fader and tone run 0..14 with the centre at 7 (mBALVal etc. default 7, :6718). */
        const val CENTRE = 7
        const val LEVEL_MAX = 14
        const val DEFAULT_SUBWOOFER = 10
        const val DEFAULT_SLEEP_TIME = 2
        const val DEFAULT_NAV_VOLUME = 28
        const val DEFAULT_HOST_VOLUME = 30
        const val DEFAULT_GAIN = 28

        // SysProviderOpt.java key names, byte for byte.
        const val KEY_BALANCE = "Set_BalanaceLR"
        const val KEY_FADER = "Set_BalanaceFA"
        const val KEY_BASS = "Set_Bass_Val"
        const val KEY_MID = "Set_Middle_Val"
        const val KEY_TREBLE = "Set_Treble_Val"
        const val KEY_BASS_FREQ = "Set_Bass_Freq"
        const val KEY_MID_FREQ = "Set_Middle_Freq"
        const val KEY_TREBLE_FREQ = "Set_Treble_Freq"
        const val KEY_EQ_MODE = "Set_Eq_Mode"
        const val KEY_LOUDNESS = "Set_Loudness"
        const val KEY_SUBWOOFER = "Set_Subwoofer"
        const val KEY_KEY_BEEP = "Set_TouchBeep"
        const val KEY_SLEEP_TIME = "SYS_SLEEP_TIME"
        const val KEY_NAV_VOLUME = "Set_NavSoundVolume"
        const val KEY_HOST_VOLUME = "Set_CarAmplifier_HostDefaultVolume"
        const val KEY_GAIN_RADIO = "Sys_Radio_Volume_Gain"
        const val KEY_GAIN_MUSIC = "Sys_Music_Volume_Gain"
        const val KEY_GAIN_MOVIE = "Sys_Movie_Volume_Gain"
        const val KEY_GAIN_BT_CALL = "Sys_BT_Volume_Gain"
        const val KEY_GAIN_BT_MUSIC = "Sys_BT_Music_Volume_Gain"
        const val KEY_GAIN_TV = "Sys_TV_Volume_Gain"
        const val KEY_GAIN_DVD = "Sys_Dvd_Volume_Gain"
        const val KEY_GAIN_AUX = "Sys_Aux_Volume_Gain"
        const val KEY_GAIN_USB = "Sys_Car_USB_Volume_Gain"
        const val KEY_GAIN_OTHER = "Sys_Other_Volume_Gain"
        const val KEY_DSP_LOUD = "Set_Dsp_Loud_On_Off_Key"

        private const val TRUE = "1"
        private const val FALSE = "0"

        private fun flag(on: Boolean) = if (on) TRUE else FALSE

        /**
         * The table from SysVar rows; a missing or unparsable row keeps its default, the way
         * `getRecordInteger(key, default)` does. Never throws on a stale store.
         */
        fun fromRows(rows: Map<String, String>): McuSetup {
            val defaults = McuSetup()

            fun int(key: String, default: Int): Int = rows[key]?.trim()?.toIntOrNull() ?: default
            fun bool(key: String, default: Boolean): Boolean = when (rows[key]?.trim()) {
                TRUE -> true
                FALSE -> false
                else -> default
            }

            return McuSetup(
                balance = int(KEY_BALANCE, defaults.balance),
                fader = int(KEY_FADER, defaults.fader),
                tone = Tone(
                    bass = int(KEY_BASS, CENTRE),
                    mid = int(KEY_MID, CENTRE),
                    treble = int(KEY_TREBLE, CENTRE),
                    bassFreq = int(KEY_BASS_FREQ, 0),
                    midFreq = int(KEY_MID_FREQ, 0),
                    trebleFreq = int(KEY_TREBLE_FREQ, 0),
                ),
                eqMode = int(KEY_EQ_MODE, defaults.eqMode),
                loudness = bool(KEY_LOUDNESS, defaults.loudness),
                subwoofer = int(KEY_SUBWOOFER, defaults.subwoofer),
                keyBeep = if (bool(KEY_KEY_BEEP, false)) Beep.ON else Beep.OFF,
                sleepTime = int(KEY_SLEEP_TIME, defaults.sleepTime),
                navVolume = int(KEY_NAV_VOLUME, defaults.navVolume),
                hostDefaultVolume = int(KEY_HOST_VOLUME, defaults.hostDefaultVolume),
                gains = SourceGains(
                    radio = int(KEY_GAIN_RADIO, DEFAULT_GAIN),
                    music = int(KEY_GAIN_MUSIC, DEFAULT_GAIN),
                    movie = int(KEY_GAIN_MOVIE, DEFAULT_GAIN),
                    btCall = int(KEY_GAIN_BT_CALL, DEFAULT_GAIN),
                    btMusic = int(KEY_GAIN_BT_MUSIC, DEFAULT_GAIN),
                    tv = int(KEY_GAIN_TV, DEFAULT_GAIN),
                    dvd = int(KEY_GAIN_DVD, DEFAULT_GAIN),
                    aux = int(KEY_GAIN_AUX, DEFAULT_GAIN),
                    usb = int(KEY_GAIN_USB, DEFAULT_GAIN),
                    other = int(KEY_GAIN_OTHER, DEFAULT_GAIN),
                ),
                dspLoud = bool(KEY_DSP_LOUD, defaults.dspLoud),
            )
        }
    }
}
