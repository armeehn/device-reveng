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
 * 6708-6720): the three tone bands centre on 7 of 0..14, EQ 0, loudness
 * off, subwoofer 10, key beep OFF ("0", :6515), sleep option 2, nav volume 28, host default
 * volume 30 (:9929) and every source gain 28 (:6548-6557). Balance and fader centre on 10 of
 * 0..20, the DSP app's range (BalanceModel_two.java:56-69), not eventcenter's 7.
 */
data class McuSetup(
    val balance: Int = BAL_FAD_CENTRE,
    val fader: Int = BAL_FAD_CENTRE,
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
    /** The DSP app's 48-band curve in dB, -10..10 ([DspEq]); boot re-sends it as `4F 10`. */
    val dspEq: List<Int> = DspEq.FLAT,
    /** The stock preset index ([DspEq.Preset.index]), or [DspEq.EDITED] after a band edit. */
    val dspPreset: Int = DspEq.Preset.FLAT.index,
    /** The three custom curves, stock's sp_eq_mode_custom1..3_two. */
    val dspCustom: List<List<Int>> = List(DspEq.CUSTOM_SLOTS) { DspEq.FLAT },
    /** The DSP subwoofer ([DspSound.Sub]); boot re-sends it as `4F 15`. */
    val dspSub: DspSound.Sub = DspSound.Sub(),
    /** The DSP bass boost ([DspSound.Bass]); boot re-sends it as `4F 16`. */
    val dspBass: DspSound.Bass = DspSound.Bass(),
    /** The DSP listening position ([DspSound.Field]); boot re-sends it as `4F 12` and `4F 13`. */
    val dspField: DspSound.Field = DspSound.Field(),
    /** The DSP crossover ([DspSound.Crossover]); boot re-sends it as `4F 14`. */
    val dspCrossover: DspSound.Crossover = DspSound.Crossover(),
    /** The DSP surround ([DspSound.Surround]); boot re-sends it as `4F 0F`. */
    val dspSurround: DspSound.Surround = DspSound.Surround(),
) {
    /** A preset or custom slot loads its whole curve (EqModel_Two_48.setMode, :180-188). */
    fun withDspPreset(preset: DspEq.Preset): McuSetup {
        val curve = preset.curve ?: preset.slot?.let { dspCustom.getOrNull(it) } ?: DspEq.FLAT

        return copy(dspEq = curve, dspPreset = preset.index)
    }

    /** One band moved; no preset is selected any more (setEqValue, :78-101). */
    fun withDspBand(band: Int, gain: Int): McuSetup {
        if (band !in dspEq.indices) {
            return this
        }

        val curve = dspEq.toMutableList().also { it[band] = DspEq.clamp(gain) }
        return copy(dspEq = curve, dspPreset = DspEq.EDITED)
    }

    /** The current curve into a custom slot, which becomes the preset (saveCustomerEqValues, :103-120). */
    fun withDspCustomSaved(slot: Int): McuSetup {
        if (slot !in dspCustom.indices) {
            return this
        }

        val slots = dspCustom.toMutableList().also { it[slot] = dspEq }
        return copy(dspCustom = slots, dspPreset = DspEq.Preset.custom(slot).index)
    }

    /** The EQ page's default button (EqFragment_two_48.java:546-551): flat, loudness off, slots kept. */
    fun dspReset(): McuSetup = copy(dspEq = DspEq.FLAT, dspPreset = DspEq.Preset.FLAT.index, dspLoud = false)

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
        KEY_DSP_EQ to DspEq.row(dspEq),
        KEY_DSP_PRESET to "$dspPreset",
        KEY_DSP_SUB to DspSound.subRow(dspSub),
        KEY_DSP_PHASE to flag(dspSub.reversePhase),
        KEY_DSP_AMP to flag(dspSub.amplifier),
        KEY_DSP_OFF_ON to flag(dspSub.offOn),
        KEY_DSP_BASS_LEVEL to "${dspBass.level}",
        KEY_DSP_BASS_FREQ to "${dspBass.freq}",
        KEY_DSP_SEAT to "${dspField.seat.mode}",
        KEY_DSP_FRONT_LP to "${dspCrossover.frontLp}",
        KEY_DSP_FRONT_HP to "${dspCrossover.frontHp}",
        KEY_DSP_REAR_LP to "${dspCrossover.rearLp}",
        KEY_DSP_REAR_HP to "${dspCrossover.rearHp}",
        KEY_DSP_LP_SLOPE to "${dspCrossover.lpSlope.ordinal}",
        KEY_DSP_HP_SLOPE to "${dspCrossover.hpSlope.ordinal}",
        KEY_DSP_SURROUND_ON to flag(dspSurround.on),
        KEY_DSP_SURROUND_CENTRE to flag(dspSurround.centre),
        KEY_DSP_SURROUND_MODE to "${dspSurround.mode.wire}",
    ) + KEY_DSP_CUSTOM.zip(dspCustom.map(DspEq::row)) +
        KEY_DSP_DELAY.zip(dspField.delays.map { "$it" }) +
        KEY_DSP_GAIN.zip(dspField.gains.map { "$it" })

    companion object {
        /** Tone runs 0..14 with the centre at 7 (mBassVal etc. default 7, :6718). */
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

        // The DSP app's own ShareUtil keys (com.choiceway.dsp Constants.java:109-125).
        const val KEY_DSP_EQ = "sp_eq_values_two_48"
        const val KEY_DSP_PRESET = "sp_eq_mode_index_two"
        val KEY_DSP_CUSTOM = listOf("sp_eq_mode_custom1_two", "sp_eq_mode_custom2_two", "sp_eq_mode_custom3_two")
        const val KEY_DSP_SUB = "sp_strong_bass_values_two"
        const val KEY_DSP_PHASE = "phase_values"
        const val KEY_DSP_AMP = "amplifier_values"
        const val KEY_DSP_OFF_ON = "off_on_values"
        const val KEY_DSP_BASS_LEVEL = "bass_progress_values"
        const val KEY_DSP_BASS_FREQ = "frequency_values"
        const val KEY_DSP_SEAT = "drive_mode_values"
        const val KEY_DSP_FRONT_LP = "front_sp_low_pass_filter_value_two"
        const val KEY_DSP_FRONT_HP = "front_sp_high_pass_filter_value_two"
        const val KEY_DSP_REAR_LP = "rear_sp_low_pass_filter_value_two"
        const val KEY_DSP_REAR_HP = "rear_sp_high_pass_filter_value_two"
        const val KEY_DSP_LP_SLOPE = "lpmode_value"
        const val KEY_DSP_HP_SLOPE = "hpmode_value"
        const val KEY_DSP_SURROUND_ON = "SOUND_OFFON_VALUES"
        const val KEY_DSP_SURROUND_CENTRE = "SOUND_ZHONGZHI_VALUES"
        const val KEY_DSP_SURROUND_MODE = "SOUND_MODE_VALUES"

        /** Per speaker in [DspSound.Speaker] order: stock's *_MILE (distance) and *_BL (gain) rows. */
        val KEY_DSP_DELAY = listOf(
            "left_front_mile_values", "right_front_mile_values", "left_rear_mile_values",
            "right_rear_mile_values", "front_mile_values",
        )
        val KEY_DSP_GAIN = listOf(
            "left_front_bl_values", "right_front_bl_values", "left_rear_bl_values",
            "right_rear_bl_values", "front_bl_values",
        )

        private const val TRUE = "1"
        private const val FALSE = "0"

        private fun flag(on: Boolean) = if (on) TRUE else FALSE

        /** Stock's float filter rows (saveLowPassFilterValue, :241-244); a bad row keeps the default. */
        private fun crossover(rows: Map<String, String>): DspSound.Crossover {
            val d = DspSound.Crossover()

            fun hz(key: String, default: Int) = DspSound.number(rows[key]) ?: default
            fun slope(key: String) =
                DspSound.Slope.values().getOrNull(DspSound.number(rows[key]) ?: 0) ?: DspSound.Slope.BESSEL

            return DspSound.Crossover(
                frontLp = hz(KEY_DSP_FRONT_LP, d.frontLp),
                frontHp = hz(KEY_DSP_FRONT_HP, d.frontHp),
                rearLp = hz(KEY_DSP_REAR_LP, d.rearLp),
                rearHp = hz(KEY_DSP_REAR_HP, d.rearHp),
                lpSlope = slope(KEY_DSP_LP_SLOPE),
                hpSlope = slope(KEY_DSP_HP_SLOPE),
            ).clamped()
        }

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
                dspEq = DspEq.parse(rows[KEY_DSP_EQ]),
                dspPreset = int(KEY_DSP_PRESET, defaults.dspPreset),
                dspCustom = KEY_DSP_CUSTOM.map { DspEq.parse(rows[it]) },
                dspSub = DspSound.parseSub(
                    rows[KEY_DSP_SUB],
                    DspSound.Sub(
                        reversePhase = bool(KEY_DSP_PHASE, false),
                        amplifier = bool(KEY_DSP_AMP, false),
                        offOn = bool(KEY_DSP_OFF_ON, false),
                    ),
                ),
                dspBass = DspSound.Bass(
                    level = DspSound.number(rows[KEY_DSP_BASS_LEVEL]) ?: 0,
                    freq = DspSound.number(rows[KEY_DSP_BASS_FREQ]) ?: 0,
                ).clamped(),
                dspField = DspSound.Field(
                    delays = KEY_DSP_DELAY.map { DspSound.number(rows[it]) ?: 0 },
                    gains = KEY_DSP_GAIN.map { DspSound.number(rows[it]) ?: DspSound.GAIN_FLAT },
                    seat = DspSound.Seat.of(DspSound.number(rows[KEY_DSP_SEAT]) ?: DspSound.Seat.ALL.mode),
                ).clamped(),
                dspCrossover = crossover(rows),
                dspSurround = DspSound.Surround(
                    on = bool(KEY_DSP_SURROUND_ON, false),
                    centre = bool(KEY_DSP_SURROUND_CENTRE, false),
                    mode = DspSound.SurroundMode.values().firstOrNull {
                        it.wire == DspSound.number(rows[KEY_DSP_SURROUND_MODE])
                    } ?: DspSound.SurroundMode.MUSIC,
                ),
            )
        }
    }
}
