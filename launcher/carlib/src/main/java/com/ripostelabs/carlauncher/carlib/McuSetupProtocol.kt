package com.ripostelabs.carlauncher.carlib

/**
 * McuSetupProtocol — the MCU setup frames eventcenter built from its SysVar rows, typed.
 *
 *     McuSetupStore ──▶ McuSetupProtocol.xxx(value) ──▶ McuOwner.send ──▶ /dev/ttyHS1
 *     McuOwner ──onOther(76/77/7A/7B)──▶ McuSetupProtocol.balance/eqMode/... ──▶ McuSetupStore
 *
 * Every frame is one vendor `send*` (EventService.java, line cited on each), wrapped by the
 * same writer as the rest of [McuOwnerProtocol] (`SendThread.sendData`, :10650). Values are
 * clamped to a byte, never wrapped. What the MCU does with a value outside the vendor UI's
 * range is unverified; the ranges the screens offer are the vendor's.
 *
 * Not typed here, on purpose: the `0F` factory bit-field (`sendFactoryMcuSet`, :9984), the
 * `10` block (`sendVolumeFader`, :9954), `45` baud (`sendMcuBaudRate`) and the 32-byte DSP
 * table (`sendSndFreqArray`, :9149). Their bytes are config-derived (car id, panel type,
 * encoder wiring) and unknown for this unit, so a wrong guess could change what the reverse
 * line or the radar does. They stay replayed in [McuOwnerProtocol.vendorInit] until a capture
 * proves them.
 */
object McuSetupProtocol {

    private const val OP_SETUP = 0x05          // sendSetup, :6389
    private const val OP_BEEP = 0x06           // beep, :4308
    private const val OP_SYSTEM_KEY = 0x08     // sendSystemKey, :4264
    private const val OP_EQ_MODE = 0x09        // sendEQMode, :4293
    private const val OP_SUBWOOFER = 0x15      // sendSndSWVol, :9542
    private const val OP_TONE = 0x22           // sendAudioValue, :9424
    private const val OP_NAV_VOLUME = 0x26     // sendGPSVol, :9533
    private const val OP_BALANCE_FADER = 0x2F  // sendBalFadValue, :9443
    private const val OP_TIMER = 0x49          // sendSleepTime / sendVolumeGain / sendAccDelayTime
    private const val OP_CONFIG = 0x4F         // sendCarDefaultHostVol / sendDspLoud

    private const val SETUP_KEY_BEEP = 0x02    // sendSetup(2, !beep), :9925
    private const val TIMER_SLEEP = 0x05       // :9370
    private const val TIMER_GAINS = 0x08       // :9662
    private const val TIMER_ACC_DELAY = 0x17   // :3171
    private const val CONFIG_HOST_VOLUME = 0x05 // :3162
    private const val CONFIG_DSP_LOUD = 0x0E   // :15053

    /** `SYS_LOUD` (EventUtils.java:1790): the system key that flips loudness; the MCU answers `7B`. */
    private const val SYSTEM_KEY_LOUDNESS = 13

    /** sendSleepTime (:9361-9371): option 1/2/3 to the MCU's value, anything else the fallback. */
    private const val SLEEP_FALLBACK = 480
    private val SLEEP_TABLE = mapOf(1 to 960, 2 to 1440, 3 to 2880)

    private const val SECONDS_PER_MINUTE = 60
    private const val BYTE = 0xFF
    private const val BALANCE_PAYLOAD = 2
    private const val TONE_PAYLOAD = 4

    /** `2F balance fader`, amp domain 0..14 with 7 at centre (sendBalFadValue, :9440). */
    fun balanceFader(balance: Int, fader: Int): ByteArray =
        McuSerial.encode(OP_BALANCE_FADER, bytes(balance, fader))

    /** `09 mode` (sendEQMode, :4291). The preset curves are the MCU's; 0 is the user curve. */
    fun eqMode(mode: Int): ByteArray = McuSerial.encode(OP_EQ_MODE, bytes(mode))

    /** `22 bass mid treble bassF midF trebleF` (sendAudioValue, :9420). */
    fun tone(tone: McuSetup.Tone): ByteArray = McuSerial.encode(
        OP_TONE,
        bytes(tone.bass, tone.mid, tone.treble, tone.bassFreq, tone.midFreq, tone.trebleFreq),
    )

    /** `15 level` (sendSndSWVol, :9541). */
    fun subwoofer(level: Int): ByteArray = McuSerial.encode(OP_SUBWOOFER, bytes(level))

    /** `26 level`: the navigation prompt mix level (sendGPSVol, :9532). */
    fun navVolume(level: Int): ByteArray = McuSerial.encode(OP_NAV_VOLUME, bytes(level))

    /** `08 0D`: loudness has no setter, only this toggle key; the state comes back as `7B`. */
    fun loudnessToggle(): ByteArray = McuSerial.encode(OP_SYSTEM_KEY, bytes(SYSTEM_KEY_LOUDNESS))

    /** The bare `06` (beep, :4308); the vendor sends it only while the key beep is enabled. */
    fun beep(): ByteArray = McuSerial.encode(OP_BEEP, ByteArray(0))

    /** `05 02 x` with x = 1 when the beep is OFF (sendSetup(2, !mKeyBeepEnable), :9925). */
    fun keyBeep(beep: McuSetup.Beep): ByteArray =
        McuSerial.encode(OP_SETUP, bytes(SETUP_KEY_BEEP, if (beep == McuSetup.Beep.ON) 0 else 1))

    /** `49 05 hi lo` (sendSleepTime, :9361). The unit of the MCU value is unverified. */
    fun sleepTime(option: Int): ByteArray {
        val value = SLEEP_TABLE[option] ?: SLEEP_FALLBACK
        return McuSerial.encode(OP_TIMER, bytes(TIMER_SLEEP, (value shr 8) and BYTE, value and BYTE))
    }

    /** `49 08` then the ten gains in [McuSetup.SourceGains] order (sendVolumeGain, :9661). */
    fun sourceGains(gains: McuSetup.SourceGains): ByteArray =
        McuSerial.encode(OP_TIMER, bytes(TIMER_GAINS) + bytes(*gains.asList().toIntArray()))

    /** `4F 05 level`: the amp level the MCU boots to on its own (sendCarDefaultHostVol, :3161). */
    fun hostDefaultVolume(level: Int): ByteArray =
        McuSerial.encode(OP_CONFIG, bytes(CONFIG_HOST_VOLUME, level))

    /** `4F 0E state` (sendDspLoud, :15050); the only `4F` sub-id the decompile names. */
    fun dspLoud(on: Boolean): ByteArray =
        McuSerial.encode(OP_CONFIG, bytes(CONFIG_DSP_LOUD, if (on) 1 else 0))

    /** `49 17 minutes seconds` (sendAccDelayTime, :3169). */
    fun accDelay(seconds: Int): ByteArray = McuSerial.encode(
        OP_TIMER,
        bytes(TIMER_ACC_DELAY, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE),
    )

    /**
     * What boot re-sends, in the vendor's order (initSysEventState, :3794-3800, through
     * sendFactorySet :9924-9931): key beep, host default volume, nav volume, EQ, gains. Sleep
     * is not here: [McuOwnerProtocol.StartupConfig.sleepTime] sends it in the vendor's slot,
     * from [sleepOption]. Balance, fader, tone and subwoofer are not re-sent: the MCU keeps
     * them and reports them.
     */
    fun boot(setup: McuSetup): List<ByteArray> = listOf(
        keyBeep(setup.keyBeep),
        hostDefaultVolume(setup.hostDefaultVolume),
        navVolume(setup.navVolume),
        eqMode(setup.eqMode),
        sourceGains(setup.gains),
    )

    /** The vendor's SYS_SLEEP_TIME option as the boot frame's enum: 1/2/3, anything else 8 h. */
    fun sleepOption(option: Int): McuOwnerProtocol.SleepTime = when (option) {
        1 -> McuOwnerProtocol.SleepTime.H16
        2 -> McuOwnerProtocol.SleepTime.H24
        3 -> McuOwnerProtocol.SleepTime.H48
        else -> McuOwnerProtocol.SleepTime.H8
    }

    // ── Reports: what the MCU says back, as eventcenter folded them into SysVar ────────────────

    data class Balance(val balance: Int, val fader: Int)

    /** `7A balance fader` (onCmdBalanceEvent, :2965). */
    fun balance(command: McuSerial.Command): Balance? {
        if (command.opcode != McuOpcode.BALANCE.code || command.payload.size < BALANCE_PAYLOAD) {
            return null
        }
        return Balance(at(command, 0), at(command, 1))
    }

    /** `77 mode` (onCmdEQEvent, :2909). */
    fun eqMode(command: McuSerial.Command): Int? {
        if (command.opcode != McuOpcode.EQ.code || command.payload.isEmpty()) {
            return null
        }
        return at(command, 0)
    }

    /** `7B state`, non-zero is on (onCmdLoudnessEvent, :2981). */
    fun loudness(command: McuSerial.Command): Boolean? {
        if (command.opcode != McuOpcode.LOUDNESS.code || command.payload.isEmpty()) {
            return null
        }
        return at(command, 0) != 0
    }

    /** `76 bass mid treble x` (onCmdBMTVolEvent, :2880): the levels only, the frequencies stay. */
    fun tone(command: McuSerial.Command): McuSetup.Tone? {
        if (command.opcode != McuOpcode.BMT_VOLUME.code || command.payload.size < TONE_PAYLOAD) {
            return null
        }
        return McuSetup.Tone(bass = at(command, 0), mid = at(command, 1), treble = at(command, 2))
    }

    private fun at(command: McuSerial.Command, i: Int): Int = command.payload[i].toInt() and BYTE

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].coerceIn(0, BYTE).toByte() }
}
