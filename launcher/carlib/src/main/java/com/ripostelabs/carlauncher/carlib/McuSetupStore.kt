package com.ripostelabs.carlauncher.carlib

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * McuSetupStore — the MCU setup table across boots, and the one place that changes it.
 *
 *     Settings screen ──set*()──▶ store ──▶ prefs (SysVar row names)
 *                                      └──▶ McuSetupProtocol frame ──▶ send ──▶ MCU
 *     MCU ──76/77/7A/7B──▶ McuOwner.onAudio ──▶ store (row only, nothing sent back)
 *
 * eventcenter did the same in two halves: `send*` wrote the row and the frame, and the
 * `onCmd*Event` handlers wrote the row the MCU reported (EventService.java:2880-2990). The
 * reports win: a panel EQ key changes the MCU first, and the row follows.
 *
 * Loudness is the odd one: there is no setter frame, only the SYS_LOUD toggle key, so
 * [toggleLoudness] flips the row optimistically and the `7B` report settles it.
 */
class McuSetupStore(
    context: Context,
    private val send: (ByteArray) -> Unit,
) : McuOwner.Listener {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _setup = MutableStateFlow(load())

    /** The table as last persisted; boot reads it for [McuOwnerProtocol.StartupConfig.setup]. */
    val setup: StateFlow<McuSetup> = _setup.asStateFlow()

    fun setBalanceFader(balance: Int, fader: Int) {
        val b = balance.coerceIn(0, BAL_FAD_MAX)
        val f = fader.coerceIn(0, BAL_FAD_MAX)
        update { copy(balance = b, fader = f) }
        send(McuSetupProtocol.balanceFader(b, f))
    }

    fun setEqMode(mode: Int) {
        update { copy(eqMode = mode) }
        send(McuSetupProtocol.eqMode(mode))
    }

    fun setTone(tone: McuSetup.Tone) {
        update { copy(tone = tone) }
        send(McuSetupProtocol.tone(tone))
    }

    fun setSubwoofer(level: Int) {
        update { copy(subwoofer = level) }
        send(McuSetupProtocol.subwoofer(level))
    }

    fun toggleLoudness() {
        update { copy(loudness = !loudness) }
        send(McuSetupProtocol.loudnessToggle())
    }

    fun setKeyBeep(beep: McuSetup.Beep) {
        update { copy(keyBeep = beep) }
        send(McuSetupProtocol.keyBeep(beep))
    }

    fun setSleepTime(option: Int) {
        update { copy(sleepTime = option) }
        send(McuSetupProtocol.sleepTime(option))
    }

    fun setNavVolume(level: Int) {
        update { copy(navVolume = level) }
        send(McuSetupProtocol.navVolume(level))
    }

    fun setGains(gains: McuSetup.SourceGains) {
        update { copy(gains = gains) }
        send(McuSetupProtocol.sourceGains(gains))
    }

    fun setDspLoud(on: Boolean) {
        update { copy(dspLoud = on) }
        send(McuSetupProtocol.dspLoud(on))
    }

    /** A stock preset or custom slot: the whole curve goes out as `4F 10`. */
    fun setDspPreset(preset: DspEq.Preset) {
        update { withDspPreset(preset) }
        send(McuSetupProtocol.dspEq(_setup.value.dspEq))
    }

    /** One band: `4F 11`, as the DSP app sent while dragging. */
    fun setDspBand(band: Int, gain: Int) {
        if (band !in 0 until DspEq.BANDS) {
            return
        }

        update { withDspBand(band, gain) }
        send(McuSetupProtocol.dspEqBand(band, gain))
    }

    /** Save the curve to a custom slot and select it; stock re-sends the whole curve here. */
    fun saveDspCustom(slot: Int) {
        update { withDspCustomSaved(slot) }
        send(McuSetupProtocol.dspEq(_setup.value.dspEq))
    }

    /** Flat EQ and DSP loudness off; the custom slots stay. */
    fun resetDsp() {
        update { dspReset() }
        send(McuSetupProtocol.dspEq(DspEq.FLAT))
        send(McuSetupProtocol.dspLoud(false))
    }

    /** The DSP subwoofer: cut-off, gain, phase, amplifier and the stock on/off flag, `4F 15`. */
    fun setDspSub(sub: DspSound.Sub) {
        val s = sub.clamped()
        update { copy(dspSub = s) }
        send(McuSetupProtocol.dspSub(s))
    }

    /** The DSP bass boost: level and centre frequency, `4F 16`. */
    fun setDspBass(bass: DspSound.Bass) {
        val b = bass.clamped()
        update { copy(dspBass = b) }
        send(McuSetupProtocol.dspBass(b))
    }

    /** The test tone, whatever the key-beep setting says (the vendor's `beep()` gated on it). */
    fun beep() {
        send(McuSetupProtocol.beep())
    }

    /** The `76`/`77`/`7A`/`7B` reports, decoded by the owner; fold them into the rows. */
    override fun onAudio(report: McuSetupProtocol.AudioReport) {
        when (report) {
            is McuSetupProtocol.AudioReport.BalanceFader -> update { copy(balance = report.balance, fader = report.fader) }
            is McuSetupProtocol.AudioReport.Eq -> update { copy(eqMode = report.mode) }
            is McuSetupProtocol.AudioReport.Loudness -> update { copy(loudness = report.on) }
            is McuSetupProtocol.AudioReport.Tone -> {
                val reported = report.levels
                update { copy(tone = tone.copy(bass = reported.bass, mid = reported.mid, treble = reported.treble)) }
            }
        }
    }

    private fun update(change: McuSetup.() -> McuSetup) {
        val next = _setup.value.change()
        _setup.value = next
        prefs.edit().apply { next.toRows().forEach { (k, v) -> putString(k, v) } }.apply()
    }

    private fun load(): McuSetup = McuSetup.fromRows(prefs.all.mapValues { it.value.toString() })

    private companion object {
        const val PREFS = "mcu_setup"
    }
}
