package com.ripostelabs.carlauncher.carlib

import java.nio.charset.Charset
import java.time.LocalDateTime

/**
 * McuOwnerProtocol — the conversation Android holds with the MCU when *we* own the port.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     McuOwner ──▶ McuOwnerProtocol.startup() ──▶ McuSerial.encode ──▶ /dev/ttyHS1
 *              ◀── sysEvent() / mainVolume() / key() ◀── McuSerial.Command ◀──┘
 *
 * Every byte here is transcribed from the vendor's port owner (`EventService` in
 * com.szchoiceway.eventcenter, jadx decompile, 2026-09-13) with the source line kept beside it.
 * [McuSerial] does the framing and checksum; this object only knows what to say and what a reply
 * means. Nothing here opens a port or keeps state.
 *
 * ── What the vendor does that this does not ─────────────────────────────────────────────────────
 * `initSysEventState` (EventService.java:3760-3888) also sends factory bit-fields (`0F`, ten bytes),
 * the volume fader block (`10`, ten bytes), DSP tables (`40 10`, 32 bytes) and per-source gains
 * (`49 08`). Their bit meanings are config-derived and undocumented, and the MCU keeps its own
 * copy, so [startup] leaves them out. Whether the MCU behaves identically without them is
 * UNVERIFIED on hardware; the first session at the car decides it.
 *
 * ── There is no heartbeat ───────────────────────────────────────────────────────────────────────
 * The vendor's "HANDLER_SEND_HEARTBEAT_EVENT" (msg 267, EventService.java:795-799) removes itself
 * and sends nothing. The MCU does not time out on silence. ACC is not on this wire either: the
 * vendor polls the system property `sys.gotoSleep.state` once a second (AccObserver.java:19-46).
 */
object McuOwnerProtocol {

    /** Outer opcodes Android sends. The RX side is [McuOpcode]. */
    private const val OP_MODE = 0x01          // sendMode, EventService.java:3933
    private const val OP_RADIO_KEY = 0x02     // sendRadioKey, :3958
    private const val OP_SETUP = 0x05         // sendSetup, :6389; index 05 = main volume, :4384
    private const val OP_MUTE = 0x0A          // sendMuteState, :4319
    private const val OP_USER_FREQ = 0x0C     // sendUserFreq, :4300
    private const val OP_RTC = 0x13           // sendRTCTimer, :9469
    private const val OP_BACKLIGHT = 0x2E     // sendBacklight, :9639-9659

    private const val SETUP_RDS = 0x00
    private const val SETUP_ZONE = 0x01
    private const val SETUP_MAIN_VOLUME = 0x05

    /** Backlight fine-tune bounds the vendor always sends (EventService.java:9643). */
    private const val BACKLIGHT_FINE_LOW = 80
    private const val BACKLIGHT_FINE_HIGH = 200

    /** `powerOff()` repeats SRC_POWEROFF this many times, 50 ms apart (EventService.java:2702-2727). */
    const val POWER_OFF_REPEATS = 5
    const val POWER_OFF_GAP_MS = 50L

    /** `sendDataWaitAck`: wait this long for MODE_ACK, up to this many sends (EventService.java:10583). */
    const val ACK_TIMEOUT_MS = 500L
    const val ACK_ATTEMPTS = 3

    private const val RTC_EPOCH_YEAR = 2000
    private const val BYTE = 0xFF
    private const val BIT7 = 0x80

    /** `73` RADIO_EVENT sub-commands, the switch in onCmdRadioEvent (EventService.java:2732-2760). */
    private const val RADIO_STATE = 0
    private const val RADIO_BAND = 1
    private const val RADIO_PRESET = 2
    private const val RADIO_FREQ = 3
    private const val RADIO_FREQ_LIST = 4
    private const val RADIO_PTY = 5
    private const val RADIO_PS_NAME = 6
    private const val RADIO_BAND_ALT = 7
    private const val RADIO_FREQ_LIST_ALT = 8

    /** Band ids the gateway accepts, 0..2 FM1..3 and 3..6 AM (onRadioBndNum, :2789). */
    const val RADIO_BAND_MAX = 6

    /** Preset slots are 0..5 (onRadioBndNum :2795, onRadioCurNum :2802). */
    const val RADIO_PRESET_COUNT = 6

    /** The station list has 42 slots (`mRadioFreqList`, :256; onRadioFreqList :2820). */
    const val RADIO_FREQ_LIST_SIZE = 42

    /**
     * `new String(bArr, 2, …)` (:2840) decodes with the platform default, UTF-8 on Android. RDS PS
     * is 8 characters of basic Latin, so the choice only matters for a name the MCU never sends.
     */
    private val PS_CHARSET: Charset = Charsets.UTF_8

    /** RDS pads PS to 8 characters; the MCU may pad with NUL. The vendor shows the padding as-is. */
    private val PS_PADDING = charArrayOf(' ', '\u0000')

    /**
     * `EventUtils.eSrcMode` (EventUtils.java:2005-2069). Decimal there, one byte on the wire:
     * SRC_POWERON(100) is `0x64`. Only the values a launcher needs are listed.
     */
    enum class Mode(val code: Int) {
        NONE(0),
        RADIO(1),
        BT(6),
        BT_MUSIC(7),
        MUSIC(11),
        ANDROID(14),
        CARPLAY(32),
        AUX(40),
        BACKCAR(41),
        HOME(43),
        MCU_VERSION(80),
        NULL(99),
        POWER_ON(100),
        POWER_OFF(101),
        IDLE(103),
        IDLE_RELEASE(104),
    }

    /** What the launcher must know at start; the vendor reads the same from SysVar. */
    data class StartupConfig(
        val rds: Boolean = true,
        val radioZone: Int = 0,
        val backlightDay: Int = 100,
        val backlightNight: Int = 60,
    )

    /**
     * The `71` SYS_EVENT bits, byte 1 then byte 2 (onCmdSysEvent, EventService.java:2290-2397).
     * [reverse] is what starts the vendor's camera; [illumination] is the headlamp line that
     * drives day/night; [accLine] is the hardware ACC input, distinct from the sleep property.
     */
    data class SysEvent(
        val disc: Boolean,
        val usb: Boolean,
        val rightTurn: Boolean,
        val illumination: Boolean,
        val brake: Boolean,
        val reverse: Boolean,
        val accLine: Boolean,
        val mcan: Boolean,
        val startStop: Boolean,
        val hdmi: Boolean,
        val leftTurn: Boolean,
    )

    /** `79` MAIN_VOLUME: bit 7 set means the change was silent (onCmdMainVolEvent, :2943-2964). */
    data class MainVolume(val level: Int, val silent: Boolean)

    /** `78` MUTE: same shape, low bits non-zero = muted (onCmdMuteEvent, EventService.java:2921). */
    data class Mute(val muted: Boolean, val silent: Boolean)

    /**
     * One `73` RADIO_EVENT, keyed by its first payload byte (onCmdRadioEvent, EventService.java:2729).
     * The vendor caches each into a field that an AIDL getter then returns; [RadioStateHolder] is
     * that cache on our side. Frequencies are the tuner's own units, unscaled by the gateway: FM
     * in 10 kHz (8750 = 87.50 MHz, the field's default at :255) and AM in kHz, which is how the
     * vendor radio prints them (RadioUIControllerRotate.java:958-962, `%d.%02d` for band ≤ 2).
     */
    sealed class RadioEvent {
        /**
         * Sub 0 (onRadioState, :2763-2783): byte 2 is the icon set, byte 3 the feature flags.
         *
         *     byte 2: bit0 stereo icon, bit1 TP icon, bit2 traffic announcement, bit3 no PTY
         *     byte 3: bit0 RDS, bit1 PTY, bit2 AF, bit3 TA, bit4 ST/mono, bit5 DX/LOC, bit6 AMS, bit7 APS
         *
         * [stereoIcon] is what `getRadioSteroIconState` returns; [stMono] is the ST/MONO *setting*
         * (`getRadioSTMonoState`), a different bit.
         */
        data class State(
            val stereoIcon: Boolean,
            val tpIcon: Boolean,
            val traffic: Boolean,
            val noPty: Boolean,
            val rds: Boolean,
            val pty: Boolean,
            val af: Boolean,
            val ta: Boolean,
            val stMono: Boolean,
            val loc: Boolean,
            val ams: Boolean,
            val aps: Boolean,
        ) : RadioEvent()

        /**
         * Sub 1 and 7 (onRadioBndNum, :2786-2799): byte 2 the band, byte 3 the preset slot. Each
         * is taken only when in range, independently, so either may be null here.
         */
        data class Band(val band: Int?, val preset: Int?) : RadioEvent()

        /** Sub 2 (onRadioCurNum, :2801-2806): byte 2 the preset slot, 0..5. */
        data class Preset(val preset: Int) : RadioEvent()

        /** Sub 3 (onRadioCurFreq, :2808-2816): bytes 2-3 big-endian, in the band's units. */
        data class Frequency(val freq: Int) : RadioEvent()

        /** Sub 4 and 8 (onRadioFreqList, :2819-2825): byte 2 the slot 0..41, bytes 3-4 big-endian. */
        data class FreqList(val index: Int, val freq: Int) : RadioEvent()

        /** Sub 5 (onRadioPTYType, :2827-2833): byte 2, an index into the vendor's 32-entry PTY table. */
        data class Pty(val pty: Int) : RadioEvent()

        /**
         * Sub 6 (onRadioPSName, :2835-2845): every byte after the sub-command, `new String(bArr, 2,
         * bArr.length - 3)` with CK still on the body. Trailing PS padding is trimmed here; the
         * vendor keeps it and the TextView hides it.
         */
        data class StationName(val name: String) : RadioEvent()
    }

    fun mode(mode: Mode): ByteArray = McuSerial.encode(OP_MODE, bytes(mode.code))

    fun setup(index: Int, value: Int): ByteArray = McuSerial.encode(OP_SETUP, bytes(index, value))

    fun backlight(day: Int, night: Int): ByteArray =
        McuSerial.encode(OP_BACKLIGHT, bytes(day, night, BACKLIGHT_FINE_LOW, BACKLIGHT_FINE_HIGH))

    fun mainVolume(level: Int): ByteArray = setup(SETUP_MAIN_VOLUME, level)

    fun mute(on: Boolean): ByteArray = McuSerial.encode(OP_MUTE, bytes(if (on) 1 else 0))

    fun radioKey(key: Int): ByteArray = McuSerial.encode(OP_RADIO_KEY, bytes(key))

    /** `0C fH fL band` where band is 0 for FM and 1 for AM (sendUserFreq, EventService.java:4300). */
    fun userFreq(freq: Int, fm: Boolean): ByteArray =
        McuSerial.encode(OP_USER_FREQ, bytes(freq ushr 8, freq, if (fm) 0 else 1))

    /** `13 yy MM dd HH mm ss`, year from 2000 (sendRTCTimer, EventService.java:9469). */
    fun rtc(now: LocalDateTime): ByteArray = McuSerial.encode(
        OP_RTC,
        bytes(now.year - RTC_EPOCH_YEAR, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second),
    )

    /**
     * Everything `initSysEventState` sends before its ACK-gated `SRC_NULL` (EventService.java:3790-3830),
     * minus the config blocks named in the header. The caller sends [Mode.NULL] afterwards with
     * [ACK_ATTEMPTS] tries of [ACK_TIMEOUT_MS] each, and continues either way, as the vendor does.
     */
    fun startup(config: StartupConfig): List<ByteArray> = listOf(
        mode(Mode.POWER_ON),
        mode(Mode.MCU_VERSION),
        setup(SETUP_RDS, if (config.rds) 0 else 1),
        setup(SETUP_ZONE, config.radioZone),
        backlight(config.backlightDay, config.backlightNight),
    )

    /**
     * The POWER key path (EventService.java:2696-2727): stamp the clock, then SRC_POWEROFF
     * [POWER_OFF_REPEATS] times. The caller spaces the repeats by [POWER_OFF_GAP_MS].
     */
    fun powerOff(now: LocalDateTime): List<ByteArray> =
        listOf(rtc(now)) + List(POWER_OFF_REPEATS) { mode(Mode.POWER_OFF) }

    /** MODE_ACK carries the mode it acknowledges in its first byte (onCmdModeAck, :2186-2190). */
    fun isModeAck(command: McuSerial.Command, mode: Mode): Boolean =
        command.opcode == McuOpcode.MODE_ACK.code &&
            command.payload.isNotEmpty() &&
            (command.payload[0].toInt() and BYTE) == mode.code

    fun sysEvent(command: McuSerial.Command): SysEvent? {
        if (command.opcode != McuOpcode.SYS_EVENT.code || command.payload.size < 2) {
            return null
        }

        val b1 = command.payload[0].toInt() and BYTE
        val b2 = command.payload[1].toInt() and BYTE
        return SysEvent(
            disc = b1 and 0x80 != 0,
            usb = b1 and 0x40 != 0,
            rightTurn = b1 and 0x10 != 0,
            illumination = b1 and 0x08 != 0,
            brake = b1 and 0x04 != 0,
            reverse = b1 and 0x02 != 0,
            accLine = b1 and 0x01 != 0,
            mcan = b2 and 0x80 != 0,
            startStop = b2 and 0x40 != 0,
            hdmi = b2 and 0x08 != 0,
            leftTurn = b2 and 0x01 != 0,
        )
    }

    fun mainVolume(command: McuSerial.Command): MainVolume? {
        if (command.opcode != McuOpcode.MAIN_VOLUME.code || command.payload.isEmpty()) {
            return null
        }

        val raw = command.payload[0].toInt() and BYTE
        return MainVolume(level = raw and BIT7.inv(), silent = raw and BIT7 != 0)
    }

    fun mute(command: McuSerial.Command): Mute? {
        if (command.opcode != McuOpcode.MUTE.code || command.payload.isEmpty()) {
            return null
        }

        val raw = command.payload[0].toInt() and BYTE
        return Mute(muted = raw and BIT7.inv() != 0, silent = raw and BIT7 != 0)
    }

    /**
     * Decode a `73` RADIO_EVENT. Null for another opcode, a body the vendor would also ignore
     * (too short, out-of-range slot) or a sub-command it has no case for, so the caller can log it.
     */
    fun radioEvent(command: McuSerial.Command): RadioEvent? {
        if (command.opcode != McuOpcode.RADIO_EVENT.code || command.payload.isEmpty()) {
            return null
        }

        // payload[i] is the vendor's bArr[i + 1]: the opcode is ahead of it, CK behind.
        val p = command.payload
        fun at(i: Int): Int = p[i].toInt() and BYTE
        fun u16(hi: Int): Int = (at(hi) shl 8) or at(hi + 1)

        return when (at(0)) {
            RADIO_STATE -> {
                if (p.size < 3) {
                    return null
                }
                val icons = at(1)
                val flags = at(2)
                RadioEvent.State(
                    stereoIcon = icons and 0x01 != 0,
                    tpIcon = icons and 0x02 != 0,
                    traffic = icons and 0x04 != 0,
                    noPty = icons and 0x08 != 0,
                    rds = flags and 0x01 != 0,
                    pty = flags and 0x02 != 0,
                    af = flags and 0x04 != 0,
                    ta = flags and 0x08 != 0,
                    stMono = flags and 0x10 != 0,
                    loc = flags and 0x20 != 0,
                    ams = flags and 0x40 != 0,
                    aps = flags and 0x80 != 0,
                )
            }

            RADIO_BAND, RADIO_BAND_ALT -> {
                if (p.size < 3) {
                    return null
                }
                val band = at(1).takeIf { it <= RADIO_BAND_MAX }
                val preset = at(2).takeIf { it < RADIO_PRESET_COUNT }
                if (band == null && preset == null) {
                    return null
                }
                RadioEvent.Band(band, preset)
            }

            RADIO_PRESET -> {
                if (p.size < 2 || at(1) >= RADIO_PRESET_COUNT) {
                    return null
                }
                RadioEvent.Preset(at(1))
            }

            RADIO_FREQ -> {
                if (p.size < 3) {
                    return null
                }
                RadioEvent.Frequency(u16(1))
            }

            RADIO_FREQ_LIST, RADIO_FREQ_LIST_ALT -> {
                if (p.size < 4 || at(1) >= RADIO_FREQ_LIST_SIZE) {
                    return null
                }
                RadioEvent.FreqList(index = at(1), freq = u16(2))
            }

            RADIO_PTY -> {
                if (p.size < 2) {
                    return null
                }
                RadioEvent.Pty(at(1))
            }

            RADIO_PS_NAME -> {
                if (p.size < 2) {
                    return null
                }
                val text = String(p, 1, p.size - 1, PS_CHARSET)
                RadioEvent.StationName(text.trimEnd(*PS_PADDING))
            }

            else -> null
        }
    }

    /** `72` KEY_EVENT: the panel key code (onCmdKeyEvent, EventService.java:2401; codes in EventUtils.java:1470-1651). */
    fun key(command: McuSerial.Command): Int? {
        if (command.opcode != McuOpcode.KEY_EVENT.code || command.payload.isEmpty()) {
            return null
        }

        return command.payload[0].toInt() and BYTE
    }

    /** Panel key codes this launcher acts on (EventUtils.java:1470-1651). */
    object Key {
        const val POWER = 0x01
        const val MODE = 0x10
        const val MUTE = 0x11
        const val VOLUME_UP = 0x12
        const val VOLUME_DOWN = 0x13
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and BYTE).toByte() }
}
