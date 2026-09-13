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
