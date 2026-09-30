package com.ripostelabs.carlauncher.carlib

/**
 * McuFactorySet — the `0F` factory bit-field and the ACC delay, built from SysVar rows the way
 * eventcenter built them (`sendFactoryMcuSet`, EventService.java:9984-10250, and
 * `sendAccDelayTime`, :3169-3175).
 *
 *     rows (SysVarLocalStore) ──▶ payload(): 10 bytes ──▶ `0F b1..b10` ──▶ McuOwner.send
 *                                  └─ a missing row falls back to [UNIT_ROWS], not the code default
 *
 * Stock sent it at every boot (`sendFactorySet`, :9924) and whenever one of its rows changed
 * (`changeSetup`, :4675-4811). On Riposte OS 0.2 nothing sent it, so the reverse mute, radar
 * tone, auto antenna, ACC-off delay, sleep switch and screen-off-with-ACC rows changed nothing.
 *
 * Bytes 3, 7 and 9 carry car id, panel resolution and A/C supplier. They come from this unit's
 * own factorySet.xml ([UNIT_ROWS]), so the frame we send is the one stock sent, until a user
 * row flips its bit. The stock code defaults are wrong for this car (car id 0, supplier 0).
 */
object McuFactorySet {

    private const val OP_FACTORY = 0x0F
    private const val PAYLOAD = 10
    private const val BYTE = 0xFF

    /** getScreenResolution (:9891-9920): 1920x720, the GT6 panel. */
    const val PANEL_1920X720 = 8
    private const val PANEL_MASK = 0x0F
    private const val FADER_GEAR_SHIFT = 5

    /** Sys_McuSet bits 0-1 carry Sys_LogoType's high byte (:10039). */
    private const val MCU_SET_LOW_BITS = 0x03
    private const val CAR_ID_SHIFT = 2
    private const val SUPPLIER_SHIFT = 2

    // SysProviderOpt.java key names, byte for byte.
    const val KEY_LOGO_TYPE = "Sys_LogoType"
    const val KEY_MCU_SET = "Sys_McuSet"
    const val KEY_CAR_INFO_ID = "Sys_CarInfor_ID"
    const val KEY_HALF_WAVE_ENCODER = "Sys_Encoder_Setengah_Gelombang"
    const val KEY_AUDIO_FADER = "SYS_AUDIO_FADER"
    const val KEY_AUDIO_FADER_GEAR = "SYS_AUDIO_FADER_GEAR"
    const val KEY_SUOLUODE = "Sys_Suoluode_Background"
    const val KEY_PANEL_LIGHT = "Sys_MCU_Panel_Light_Key"
    const val KEY_ENCODER_SWAP = "Sys_Encoder_Swapping_Key"
    const val KEY_ENCODER_SWITCH = "Sys_Encoder_Switch"
    const val KEY_SOFT_LIGHT = "Sys_Mcu_soft_light_control_Set"
    const val KEY_SUPER_HIGH_CAR = "Sys_SuperHICar"
    const val KEY_ADJUST_OEM_VOLUME = "Sys_ajust_the_original_car_volume"
    const val KEY_OEM_AMP = "SYS_ORIGINAL_WITH_AMP"
    const val KEY_AUTO_ANTENNA = "Sys_Auto_Antenna_Set"
    const val KEY_BACKCAR_TYPE = "Sys_Backcar_type_key_set"
    const val KEY_360 = "Sys_360_Set"
    const val KEY_RADAR_TONE_TYPE = "Sys_RadarToneType"
    const val KEY_SOFT_REVERSE = "Sys_Mcu_soft_back_car_Set"
    const val KEY_RADAR_TONE = "Sys_RadarToneEnable"
    const val KEY_GOLF7 = "Can_Golf7Enable"
    const val KEY_NDEBUG = "Sys_NDebug_Switch_Key"
    const val KEY_SCREEN_OFF_WITH_ACC = "Sys_Screen_Off_When_Acc_Change"
    const val KEY_CARCOOL_INCH = "Sys_CarCoolScreenInch"
    const val KEY_DSP_SND = "Sys_DSP_Snd_Set"
    const val KEY_RIGHT_SIGNAL_WEAKEN = "Sys_RightSignalWeaken"
    const val KEY_REVERSE_SIGNAL_WEAKEN = "Sys_ReverseSignalWeaken"
    const val KEY_BIT_DEPTH = "Sys_ScreenBitDepth"
    const val KEY_ENCODER_AIR = "Sys_Encoder_Air_Function"
    const val KEY_POWER_OFF_DELAY = "Sys_Power_Off_Delay"
    const val KEY_LVDS_360 = "SYS_LVDS_360_ENABLE"
    const val KEY_RADIO_CRYSTAL = "SYS_RADIO_CRYSTLE_TYPE"
    const val KEY_TW8836 = "SYS_TW8836_ENABLE"
    const val KEY_TV_OUT = "SYS_TVOUT_ENABLE"
    const val KEY_FAN = "Sys_Fan_Key"
    const val KEY_MIX_GPS = "Sys_Mix_Gps_Voice"
    const val KEY_AIR_SUPPLIER = "Sys_camry_air_Supplier_id"
    const val KEY_SLEEP_SWITCH = "Sys_Sleep_Switch"
    const val KEY_MULTIPLE_CAMERAS = "Sys_Multpile_Cameras_Key"
    const val KEY_REVERSE_POWER = "Reverse_Constant_Power_Supply_Key"
    const val KEY_ACC_DELAY = "Sys_Acc_Delay"
    const val KEY_SOUND_WHEN_REVERSING = "Sys_Sound_when_reversing"
    const val KEY_REVERSING_ATTENUATION = "Sys_Reversing_Attenuation"

    private const val TRUE = "1"
    private const val FALSE = "0"

    /**
     * This unit's factorySet.xml (share backups, `rav4/reconnect/baselines/hvac-backup-0014`),
     * plus the stock code default for the rows that file lacks. Every row [payload] reads.
     */
    val UNIT_ROWS: Map<String, String> = mapOf(
        KEY_LOGO_TYPE to "2",
        KEY_MCU_SET to "20",
        KEY_CAR_INFO_ID to "9",
        KEY_HALF_WAVE_ENCODER to TRUE,
        KEY_AUDIO_FADER to FALSE,
        KEY_AUDIO_FADER_GEAR to "3",
        KEY_SUOLUODE to FALSE,        // absent, code default
        KEY_PANEL_LIGHT to FALSE,
        KEY_ENCODER_SWAP to FALSE,
        KEY_ENCODER_SWITCH to FALSE,
        KEY_SOFT_LIGHT to FALSE,
        KEY_SUPER_HIGH_CAR to "0",
        KEY_ADJUST_OEM_VOLUME to "0",
        KEY_OEM_AMP to FALSE,         // absent, code default
        KEY_AUTO_ANTENNA to TRUE,
        KEY_BACKCAR_TYPE to "1",
        KEY_360 to FALSE,
        KEY_RADAR_TONE_TYPE to FALSE, // absent, code default
        KEY_SOFT_REVERSE to FALSE,
        KEY_RADAR_TONE to FALSE,
        KEY_GOLF7 to FALSE,           // absent, code default
        KEY_NDEBUG to "1",
        KEY_SCREEN_OFF_WITH_ACC to TRUE,
        KEY_CARCOOL_INCH to FALSE,    // absent, code default
        KEY_DSP_SND to FALSE,         // absent, code default
        KEY_RIGHT_SIGNAL_WEAKEN to FALSE, // absent, code default
        KEY_REVERSE_SIGNAL_WEAKEN to FALSE, // absent, code default
        KEY_BIT_DEPTH to TRUE,
        KEY_ENCODER_AIR to FALSE,
        KEY_POWER_OFF_DELAY to FALSE,
        KEY_LVDS_360 to FALSE,        // absent, code default
        KEY_RADIO_CRYSTAL to FALSE,   // absent, code default
        KEY_TW8836 to FALSE,          // absent, code default
        KEY_TV_OUT to TRUE,
        KEY_FAN to TRUE,              // absent, code default
        KEY_MIX_GPS to TRUE,
        KEY_AIR_SUPPLIER to "6",
        KEY_SLEEP_SWITCH to TRUE,
        KEY_MULTIPLE_CAMERAS to "0",
        KEY_REVERSE_POWER to "0",
        KEY_ACC_DELAY to "0",
        KEY_SOUND_WHEN_REVERSING to FALSE,
        KEY_REVERSING_ATTENUATION to TRUE,
    )

    /** The rows whose change re-sends `0F`: every one [payload] reads. */
    private val FRAME_KEYS: Set<String> = UNIT_ROWS.keys -
        setOf(KEY_ACC_DELAY, KEY_SOUND_WHEN_REVERSING, KEY_REVERSING_ATTENUATION)

    /**
     * What the reverse gear does to the audio (settings `clickReverseMute`,
     * ItemTextRightCheckBoxView.java:737-773). The MCU reads bits 0x20 and 0x40 of Sys_McuSet.
     * The mapping is the stock app's, odd as it looks: mute sets both, off sets only 0x40.
     */
    enum class ReverseMute {
        MUTE,
        ATTENUATE,
        OFF;

        /** Sys_McuSet with this mode's two bits, the rest kept. */
        fun mcuSet(current: Int): Int {
            val mute = if (this == MUTE) current or BIT_MUTE else current and BIT_MUTE.inv()
            val quiet = if (this == ATTENUATE) mute and BIT_QUIET.inv() else mute or BIT_QUIET

            return quiet and BYTE
        }

        /** The three rows stock wrote for this mode; Sys_McuSet goes last, it triggers the send. */
        fun rows(current: Map<String, String>): Map<String, String> = linkedMapOf(
            KEY_SOUND_WHEN_REVERSING to flag(this == MUTE),
            KEY_REVERSING_ATTENUATION to flag(this == ATTENUATE),
            KEY_MCU_SET to "${mcuSet(Rows(current).int(KEY_MCU_SET))}",
        )

        companion object {
            private const val BIT_MUTE = 0x20
            private const val BIT_QUIET = 0x40

            /** getSysReverseMute (settings ProviderHelps.java:262-269). */
            fun of(rows: Map<String, String>): ReverseMute {
                val r = Rows(rows)
                if (r.bool(KEY_SOUND_WHEN_REVERSING)) {
                    return MUTE
                }

                return if (r.bool(KEY_REVERSING_ATTENUATION)) ATTENUATE else OFF
            }
        }
    }

    /** The full `0F` frame. */
    fun frame(rows: Map<String, String>, panel: Int = PANEL_1920X720): ByteArray =
        McuSerial.encode(OP_FACTORY, payload(rows, panel))

    /** What boot sends in the vendor's slot, after the key beep (sendFactorySet, :9924-9927). */
    fun boot(rows: Map<String, String>, panel: Int = PANEL_1920X720): List<ByteArray> =
        listOf(frame(rows, panel), accDelay(rows))

    /** `49 17 min sec` from Sys_Acc_Delay in seconds (sendAccDelayTime, :3169-3175). */
    fun accDelay(rows: Map<String, String>): ByteArray = McuSetupProtocol.accDelay(Rows(rows).int(KEY_ACC_DELAY))

    /** The ten bytes after the opcode, in the order sendFactoryMcuSet fills bArr[1..10]. */
    fun payload(rows: Map<String, String>, panel: Int = PANEL_1920X720): ByteArray {
        val r = Rows(rows)
        val logo = r.int(KEY_LOGO_TYPE)
        val fader = r.bool(KEY_AUDIO_FADER)

        // bArr[2]: logo high byte in bits 0-1, the rest Sys_McuSet; bit 7 = full-wave encoder.
        val mcuSet = ((logo shr 8) and MCU_SET_LOW_BITS) or (r.int(KEY_MCU_SET) and MCU_SET_LOW_BITS.inv())
        val b2 = mcuSet.bit(7, !r.bool(KEY_HALF_WAVE_ENCODER))

        // bArr[4]: panel keys and lights; bit 4 is always set.
        val b4 = 0.bit(0, fader)
            .bit(1, !r.bool(KEY_SUOLUODE))
            .bit(3, r.bool(KEY_PANEL_LIGHT))
            .bit(4, true)
            .bit(6, r.bool(KEY_ENCODER_SWAP))
            .bit(7, r.bool(KEY_ENCODER_SWITCH))
            .bit(5, r.bool(KEY_SOFT_LIGHT))

        // bArr[5]: reverse and radar. Bit 7 is written twice; the tone switch wins (:10111-10127).
        val b5 = r.int(KEY_SUPER_HIGH_CAR)
            .bit(0, r.int(KEY_ADJUST_OEM_VOLUME) == 1)
            .bit(1, r.bool(KEY_OEM_AMP))
            .bit(2, r.bool(KEY_AUTO_ANTENNA))
            .bit(3, r.int(KEY_BACKCAR_TYPE) != 1)
            .bit(4, r.bool(KEY_360))
            .bit(7, r.bool(KEY_RADAR_TONE_TYPE))
            .bit(6, !r.bool(KEY_SOFT_REVERSE))
            .bit(7, r.bool(KEY_RADAR_TONE))
            .bit(5, r.bool(KEY_GOLF7))

        // bArr[6]: debug, ACC screen, video signal options.
        val b6 = 0.bit(0, r.int(KEY_NDEBUG) == 0)
            .bit(1, !r.bool(KEY_SCREEN_OFF_WITH_ACC))
            .bit(2, r.bool(KEY_CARCOOL_INCH))
            .bit(4, r.bool(KEY_DSP_SND))
            .bit(5, r.bool(KEY_RIGHT_SIGNAL_WEAKEN))
            .bit(6, r.bool(KEY_REVERSE_SIGNAL_WEAKEN))
            .bit(7, !r.bool(KEY_BIT_DEPTH))

        // bArr[7]: panel resolution, plus the fader gear when the audio fader is on.
        val gear = if (fader) r.int(KEY_AUDIO_FADER_GEAR) shl FADER_GEAR_SHIFT else 0
        val b7 = (panel and PANEL_MASK) or gear

        // bArr[8]: power-off delay and video chips; bit 6 is always set.
        val b8 = 0.bit(0, r.bool(KEY_ENCODER_AIR))
            .bit(1, r.bool(KEY_POWER_OFF_DELAY))
            .bit(2, r.bool(KEY_LVDS_360))
            .bit(3, r.bool(KEY_RADIO_CRYSTAL))
            .bit(5, r.bool(KEY_TW8836))
            .bit(6, true)
            .bit(7, r.bool(KEY_TV_OUT))

        // bArr[9]: fan, nav mix (inverted) and the A/C supplier id.
        val b9 = 0.bit(0, r.bool(KEY_FAN))
            .bit(1, !r.bool(KEY_MIX_GPS)) or (r.int(KEY_AIR_SUPPLIER) shl SUPPLIER_SHIFT)

        // bArr[10]: sleep switch, and the always-powered reverse camera line.
        val cameraPower = r.int(KEY_MULTIPLE_CAMERAS) == 1 || r.int(KEY_REVERSE_POWER) == 1
        val b10 = 0.bit(4, r.bool(KEY_SLEEP_SWITCH)).bit(7, cameraPower)

        val out = intArrayOf(logo, b2, r.int(KEY_CAR_INFO_ID) shl CAR_ID_SHIFT, b4, b5, b6, b7, b8, b9, b10)
        check(out.size == PAYLOAD)

        return ByteArray(PAYLOAD) { (out[it] and BYTE).toByte() }
    }

    /**
     * Re-sends on a row change, as eventcenter's `changeSetup` did. Keeps what it last sent,
     * so boot's republish of every saved row does not repeat the boot burst.
     */
    class Watcher(
        private val rows: () -> Map<String, String>,
        private val send: (ByteArray) -> Unit,
    ) {
        private var lastFrame: ByteArray? = null
        private var lastAcc: ByteArray? = null

        /** What [boot] already sent, in its order: the frame, then the ACC delay. */
        @Synchronized
        fun booted(frames: List<ByteArray>) {
            lastFrame = frames.getOrNull(0)
            lastAcc = frames.getOrNull(1)
        }

        @Synchronized
        fun onRow(key: String) {
            if (key == KEY_ACC_DELAY) {
                val acc = accDelay(rows())
                if (!acc.contentEquals(lastAcc)) {
                    lastAcc = acc
                    send(acc)
                }
                return
            }

            if (key !in FRAME_KEYS) {
                return
            }

            val frame = frame(rows())
            if (frame.contentEquals(lastFrame)) {
                return
            }

            lastFrame = frame
            send(frame)
        }
    }

    /** Rows with the unit's values under them, read the way SysProviderOpt reads (:757-763). */
    private class Rows(private val rows: Map<String, String>) {
        fun int(key: String): Int =
            rows[key]?.trim()?.toIntOrNull() ?: UNIT_ROWS.getValue(key).toInt()

        fun bool(key: String): Boolean = int(key) == 1
    }

    private fun flag(on: Boolean) = if (on) TRUE else FALSE

    /** BIT_ON / BIT_OFF (EventService.java). */
    private fun Int.bit(index: Int, on: Boolean): Int =
        if (on) this or (1 shl index) else this and (1 shl index).inv()
}
