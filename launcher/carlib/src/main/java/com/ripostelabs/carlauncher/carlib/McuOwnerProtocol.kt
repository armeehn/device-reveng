package com.ripostelabs.carlauncher.carlib

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
    private const val OP_SYSTEM_KEY = 0x08    // sendSystemKey, :4263-4268: the VOL/MUTE key echo
    private const val OP_MUTE = 0x0A          // sendMuteState, :4319
    private const val OP_BT_STATE = 0x0B      // sendBTState, :4336-4342
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

    /** `sendBTState((byte) 0)` on ACC off (EventService.java:3559); higher values are call states. */
    const val BT_DISCONNECTED = 0

    private const val RTC_EPOCH_YEAR = 2000
    private const val BYTE = 0xFF
    private const val SLEEP_STATE_WAKE = 0x01
    private const val BIT7 = 0x80

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

    /** `0B state` (sendBTState, EventService.java:4336-4342). */
    fun btState(state: Int): ByteArray = McuSerial.encode(OP_BT_STATE, bytes(state))

    /**
     * What ACC_CHANGE_EVENT sends 3 s after wake (EventService.java:465-469): `reloadParam`
     * (:3622-3643) minus the config blocks named in the header, then the same two modes again and
     * the mode to resume. The vendor resumes `mValidMode`, reset to SRC_NONE at sleep (:3556), and
     * relies on the mode's activity to switch source later (msg 290); we resume the last mode set,
     * or NONE when none was.
     */
    fun reload(config: StartupConfig, lastMode: Mode?): List<ByteArray> = listOf(
        mode(Mode.POWER_ON),
        mode(Mode.MCU_VERSION),
        backlight(config.backlightDay, config.backlightNight),
        mode(Mode.POWER_ON),
        mode(Mode.MCU_VERSION),
        mode(lastMode ?: Mode.NONE),
    )

    /** `96 01`: the MCU says it woke (onCmdMcuSleepState, EventService.java:2270-2280). */
    fun isWake(command: McuSerial.Command): Boolean =
        command.opcode == McuOpcode.SLEEP_STATE.code &&
            command.payload.isNotEmpty() &&
            (command.payload[0].toInt() and BYTE) == SLEEP_STATE_WAKE

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

    /** `72` KEY_EVENT: the panel key code (onCmdKeyEvent, EventService.java:2401; codes in EventUtils.java:1470-1651). */
    fun key(command: McuSerial.Command): Int? = panelKey(command)?.code

    // ── Keys ─────────────────────────────────────────────────────────────────────────────────────
    //
    //     72 code status        panel / encoder key, one frame per press (onCmdKeyEvent, :2401)
    //     74 slot state ? volt  resistive wheel, learned slot with press state (onCmdWheelEvent, :2847)
    //
    // What eventcenter did with a `72` besides the MCU_KEY_INFOR broadcast is transcribed in
    // [panelSystemKey] and [panelKeyPassesReverse]; the RAV4's CAN wheel is not here, it arrives
    // under 0xA5 as a frame-0x11 relay and goes to [WheelGestures].

    /**
     * Panel key codes (`EventUtils.MCU_KEY_*`, EventUtils.java:1470-1651). The launcher maps the
     * ones with a `CAR_KEY_*` twin through [SwcFallback.mcuKey]; the rest are surfaced as-is.
     */
    object Key {
        const val POWER = 0x01        // MCU_KEY_POWER, :1580
        const val NEXT = 0x02         // MCU_KEY_NEXT, :1539
        const val PREV = 0x03         // MCU_KEY_PREV, :1582
        const val PLAY = 0x04         // MCU_KEY_PLAY, :1578
        const val STOP = 0x05         // MCU_KEY_STOP, :1615
        const val PLAY_PAUSE = 0x06   // MCU_KEY_PLAYPAUSE, :1579
        const val MENU = 0x09         // MCU_KEY_MENU, :1531: the vendor's HOME
        const val MODE = 0x10         // MCU_KEY_MODE, :1533
        const val MUTE = 0x11         // MCU_KEY_MUTE, :1537
        const val VOLUME_UP = 0x12    // MCU_KEY_VOL_ADD, :1634
        const val VOLUME_DOWN = 0x13  // MCU_KEY_VOL_SUB, :1636
        const val SETUP = 0x14        // MCU_KEY_SETUP, :1608
        const val HANGUP = 0x16       // MCU_KEY_HANGUP, :1518
        const val TALK = 0x17         // MCU_KEY_TALK, :1622
        const val EQ = 0x33           // MCU_KEY_EQ, :1508
        const val RADIO = 0x36        // MCU_KEY_RADIO, :1588
        const val RETURN = 0x55       // MCU_KEY_RETURN, :1598: the vendor's BACK
        const val TASK_LIST = 0x71    // MCU_KEY_TASK_LIST, :1623
        const val VOICE = 0x74        // MCU_KEY_SHENGKONG, :1609
    }

    /**
     * One `72` frame. [status] is byte 2: the press state of a custom panel/wheel key
     * (codes 141-155 and 164-178, `onMcuToPanelCustomKey`), unused for the fixed codes. Those
     * carry no edge at all: one frame is one complete press.
     */
    data class PanelKey(val code: Int, val status: Int)

    /**
     * One `74` frame: the resistive wheel's learned [slot] (0..9), [down] from byte 2 (non-zero
     * = pressed, the vendor's WPARAM 3/4) and the [voltage] the MCU measured (byte 4). The
     * broadcast form is LPARAM = slot + 1 (onCmdWheelEvent, EventService.java:2847-2859).
     */
    data class WheelKey(val slot: Int, val down: Boolean, val voltage: Int)

    /** What `sendSystemKey` (EventService.java:4224-4268) writes as `08 xx` when no amp routing is configured. */
    enum class SystemKey(val code: Int) {
        VOLUME_UP(0),
        VOLUME_DOWN(1),
        MUTE(12),
    }

    /** The vendor takes `bArr[1] <= 9`, signed; this refuses the bytes that pass only by sign. */
    private const val WHEEL_SLOT_MAX = 9
    private const val WHEEL_MIN_PAYLOAD = 2
    private const val WHEEL_VOLTAGE_INDEX = 3

    /**
     * Keys eventcenter still acts on while the reverse camera is up; every other `72` is dropped
     * for the duration (onCmdKeyEvent, EventService.java:2406). 25/26, 80, 58 and 160-163 are
     * encoder and socket relays this launcher does not act on but must not swallow either.
     */
    private val REVERSE_SAFE_KEYS = setOf(
        Key.VOLUME_DOWN, Key.VOLUME_UP, Key.MUTE, Key.PREV, Key.NEXT,
        25, 26, 80, 58, 160, 161, 162, 163,
    )

    fun panelKey(command: McuSerial.Command): PanelKey? {
        if (command.opcode != McuOpcode.KEY_EVENT.code || command.payload.isEmpty()) {
            return null
        }

        val status = command.payload.getOrNull(1)?.toInt() ?: 0
        return PanelKey(code = command.payload[0].toInt() and BYTE, status = status and BYTE)
    }

    fun wheelKey(command: McuSerial.Command): WheelKey? {
        if (command.opcode != McuOpcode.WHEEL_EVENT.code || command.payload.size < WHEEL_MIN_PAYLOAD) {
            return null
        }

        val slot = command.payload[0].toInt() and BYTE
        if (slot > WHEEL_SLOT_MAX) {
            return null
        }

        // The vendor reads bArr[4] unconditionally; a short frame is taken as "no reading".
        val voltage = command.payload.getOrNull(WHEEL_VOLTAGE_INDEX)?.toInt() ?: 0
        return WheelKey(
            slot = slot,
            down = command.payload[1].toInt() != 0,
            voltage = voltage and BYTE,
        )
    }

    fun systemKey(key: SystemKey): ByteArray = McuSerial.encode(OP_SYSTEM_KEY, bytes(key.code))

    /** The `08` echo eventcenter sends for a volume or mute panel key (onCmdKeyEvent cases 17-19, :2547-2555). */
    fun panelSystemKey(code: Int): SystemKey? = when (code) {
        Key.VOLUME_UP -> SystemKey.VOLUME_UP
        Key.VOLUME_DOWN -> SystemKey.VOLUME_DOWN
        Key.MUTE -> SystemKey.MUTE
        else -> null
    }

    fun panelKeyPassesReverse(code: Int): Boolean = code in REVERSE_SAFE_KEYS

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and BYTE).toByte() }
}
