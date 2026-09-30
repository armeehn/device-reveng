package com.ripostelabs.carlauncher.carlib

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The amp's audio setup as last reported or sent; a null field is one nobody has said yet. */
data class AudioState(
    val eqMode: Int? = null,
    val balance: Int? = null,
    val fader: Int? = null,
    val loudness: Boolean? = null,
    val tone: McuSetup.Tone? = null,
    val subwoofer: Int? = null,
)

/**
 * OwnerAudio — the owner path behind CarService's audio calls on Riposte OS 0.2.
 *
 *     CarService.setEqMode ──▶ OwnerAudio ──09 / 2F / 15 / 06──▶ send ──▶ McuPort
 *     McuDecoder ──onAudio(76 / 77 / 7A / 7B)──▶ OwnerAudio.state ──▶ CarService.getEqMode
 *
 * The vendor getters returned cached fields that both its `send*` calls and its `onCmd*Event`
 * handlers wrote (sendEQMode :4292, sendBalFadValue :9441, sendSndSWVol :9543, reports
 * :2894-2990). This cache does the same. The subwoofer level has no report, so it is only
 * ever what was last sent. Amp loudness has no setter: the only frame is the `08 0D` toggle key.
 * DSP loudness (`4F 0E`) is a setter; [McuSetupStore.setDspLoud] owns it.
 */
class OwnerAudio(private val send: (ByteArray) -> Unit) : McuOwner.Listener {

    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    /** `09 mode`, clamped to the vendor's six presets (the EQ key cycles 0..5, EventService.java:3242). */
    fun setEqMode(mode: Int) {
        val clamped = mode.coerceIn(0, EQ_MODE_MAX)
        _state.update { it.copy(eqMode = clamped) }
        send(McuSetupProtocol.eqMode(clamped))
    }

    /** `2F balance fader`, each clamped to the DSP domain 0..20 (centre 10). */
    fun setBalanceFader(balance: Int, fader: Int) {
        val b = balance.coerceIn(0, BAL_FAD_MAX)
        val f = fader.coerceIn(0, BAL_FAD_MAX)
        _state.update { it.copy(balance = b, fader = f) }
        send(McuSetupProtocol.balanceFader(b, f))
    }

    /** `15 level`, clamped to 0..20: the vendor default is 10, the settings screens offer 0..20. */
    fun setSubwoofer(level: Int) {
        val clamped = level.coerceIn(0, SUBWOOFER_MAX)
        _state.update { it.copy(subwoofer = clamped) }
        send(McuSetupProtocol.subwoofer(clamped))
    }

    /** The bare `06`. The caller gates it: the vendor sent it only while key beep was on. */
    fun beep() {
        send(McuSetupProtocol.beep())
    }

    /** Called from the owner's pump thread; [update] keeps it atomic against the setters. */
    override fun onAudio(report: McuSetupProtocol.AudioReport) {
        when (report) {
            is McuSetupProtocol.AudioReport.Eq -> _state.update { it.copy(eqMode = report.mode) }
            is McuSetupProtocol.AudioReport.BalanceFader ->
                _state.update { it.copy(balance = report.balance, fader = report.fader) }
            is McuSetupProtocol.AudioReport.Loudness -> _state.update { it.copy(loudness = report.on) }
            is McuSetupProtocol.AudioReport.Tone -> _state.update { it.copy(tone = report.levels) }
        }
    }

    companion object {
        const val EQ_MODE_MAX = 5
        const val SUBWOOFER_MAX = 20
    }
}
