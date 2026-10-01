package com.ripostelabs.carlauncher.carlib

/**
 * OemAmp — the car's factory amplifier (JBL on trims that have it), behind the head unit.
 *
 *     open ──▶ 03 6A 05 01 A6 ──▶ box ──▶ 0xA6 report ──▶ volume, balance, fade, tone, ASL, surround
 *     tap  ──▶ 02 AD key value ──▶ box ──▶ amp ... 0xA6 report ──▶ rows
 *
 * Separate from the head unit's own DSP ([DspSound], [DspEq]): this is the amp in the car that
 * the unit feeds. A car without it never sends 0xA6, so nothing here shows.
 * Stock: report `HiworldCanParseToyota.java:559-607`, keys `HiworldToyotaAMPSetConfig.java:7-23`,
 * poll and volume step `HiworldToyotaAMPUILandscapeDefault.java:109-157`.
 */
enum class OemAmpKey(val code: Int, val range: IntRange) {
    VOLUME(1, 0..OemAmp.MAX_VOLUME),
    BALANCE(2, 0..OemAmp.MAX_SPREAD),
    FADE(3, 0..OemAmp.MAX_SPREAD),
    BASS(4, 0..OemAmp.MAX_TONE),
    MID(5, 0..OemAmp.MAX_TONE),
    TREBLE(6, 0..OemAmp.MAX_TONE),
    ASL(7, 0..1),
    SURROUND(8, 0..1),
}

/** Which way one volume press moves the amp. */
enum class VolumeStep { UP, DOWN }

/** The amp as the last 0xA6 report gave it; raw values, labels come from [OemAmp]. */
data class OemAmpState(
    val volume: Int,
    val balance: Int,
    val fade: Int,
    val bass: Int,
    val mid: Int,
    val treble: Int,
    val asl: Boolean,
    val surround: Boolean,
)

object OemAmp {

    const val REPORT_OPCODE = 0xA6
    const val MAX_VOLUME = 63

    /** Balance and fade: 0..14, 7 is the centre. */
    const val MAX_SPREAD = 14
    private const val SPREAD_CENTRE = 7

    /** Bass, mid, treble: 0..10, 5 is flat. */
    const val MAX_TONE = 10
    private const val TONE_FLAT = 5

    /** Box command that writes one amp key (`getSendToCanByteArray0`). */
    private const val CMD_SET = 0xAD
    private const val SET_LEN = 0x02

    /** Volume goes as a step, never a level: `01` up, `FF` down. */
    private const val STEP_UP = 0x01
    private const val STEP_DOWN = 0xFF

    /** Report bytes: six values, then the flags byte. */
    private const val REPORT_LEN = 7
    private const val FLAGS = 6
    private const val FLAG_SURROUND = 0x01
    private const val FLAG_ASL = 0x02

    /** `03 6A 05 01 A6`: the stock amp page's `sendQToCan(-90, 0)` on open. */
    val QUERY: IntArray = intArrayOf(0x03, 0x6A, 0x05, 0x01, REPORT_OPCODE)

    /** A 0xA6 [payload] as stock reads it, or null when it is too short to be an amp report. */
    fun decode(payload: ByteArray): OemAmpState? {
        if (payload.size < REPORT_LEN) {
            return null
        }

        val flags = u(payload, FLAGS)
        return OemAmpState(
            volume = u(payload, 0).coerceAtMost(MAX_VOLUME),
            balance = u(payload, 1),
            fade = u(payload, 2),
            bass = u(payload, 3),
            mid = u(payload, 4),
            treble = u(payload, 5),
            asl = flags and FLAG_ASL != 0,
            surround = flags and FLAG_SURROUND != 0,
        )
    }

    /** `02 AD key value` for every key but volume, which only steps ([volumeStep]). */
    fun setPayload(key: OemAmpKey, value: Int): IntArray {
        require(key != OemAmpKey.VOLUME) { "volume is a step, see volumeStep" }
        require(value in key.range) { "${key.name} takes ${key.range}, not $value" }

        return intArrayOf(SET_LEN, CMD_SET, key.code, value)
    }

    /** `02 AD 01 01|FF` from [current], or null at the end stock stops at (0 or 63). */
    fun volumeStep(current: Int, step: VolumeStep): IntArray? {
        if (step == VolumeStep.UP && current >= MAX_VOLUME) {
            return null
        }
        if (step == VolumeStep.DOWN && current <= 0) {
            return null
        }

        val code = if (step == VolumeStep.UP) STEP_UP else STEP_DOWN
        return intArrayOf(SET_LEN, CMD_SET, OemAmpKey.VOLUME.code, code)
    }

    /** Stock's balance text: `L3`, `0`, `R3`. */
    fun balanceLabel(raw: Int): String = spread(raw, "L", "R")

    /** Stock's fade text: below the centre is rear (`R3`), above it front (`F3`). */
    fun fadeLabel(raw: Int): String = spread(raw, "R", "F")

    /** Tone offset from flat: `-5` .. `0` .. `+5`. */
    fun toneLabel(raw: Int): String {
        val offset = raw - TONE_FLAT
        return if (offset > 0) "+$offset" else "$offset"
    }

    private fun spread(raw: Int, low: String, high: String): String {
        val offset = raw - SPREAD_CENTRE
        if (offset == 0) {
            return "0"
        }

        val side = if (offset < 0) low else high
        return "$side${kotlin.math.abs(offset)}"
    }

    private fun u(a: ByteArray, i: Int): Int = a[i].toInt() and 0xFF
}

/**
 * The amp volume a run of presses is heading for. The 0xA6 report lags the presses, so each
 * press counts from the level the previous one reached, never from the report:
 *
 *     taps 21, 22, 23 from 20 ──▶ UP, UP, UP        (not 1 + 2 + 3 = 6 presses)
 *
 * A report counts only while no press is outstanding. Main thread only.
 */
class OemAmpVolume(start: Int) {

    /** Where the presses sent so far have taken the amp. */
    var at: Int = start
        private set

    private var target: Int = start

    /** A new slider target; presses already sent are not repeated. */
    fun aim(level: Int) {
        target = level.coerceIn(0, OemAmp.MAX_VOLUME)
    }

    /** The next press toward the target, or null once there. */
    fun next(): VolumeStep? {
        if (at == target) {
            return null
        }

        val step = if (target > at) VolumeStep.UP else VolumeStep.DOWN
        at += if (step == VolumeStep.UP) 1 else -1
        return step
    }

    /** The amp's own level, once every press has gone out. */
    fun onReport(level: Int) {
        if (at != target) {
            return
        }

        at = level
        target = level
    }
}
