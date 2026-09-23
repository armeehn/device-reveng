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
 *     MCU ──76/77/7A/7B──▶ McuOwner.onOther ──▶ store (row only, nothing sent back)
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
        val b = balance.coerceIn(0, McuSetup.LEVEL_MAX)
        val f = fader.coerceIn(0, McuSetup.LEVEL_MAX)
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

    /** The test tone, whatever the key-beep setting says (the vendor's `beep()` gated on it). */
    fun beep() {
        send(McuSetupProtocol.beep())
    }

    /** The `76`/`77`/`7A`/`7B` reports reach the owner's catch-all; fold them into the rows. */
    override fun onOther(command: McuSerial.Command) {
        McuSetupProtocol.balance(command)?.let { update { copy(balance = it.balance, fader = it.fader) }; return }
        McuSetupProtocol.eqMode(command)?.let { update { copy(eqMode = it) }; return }
        McuSetupProtocol.loudness(command)?.let { update { copy(loudness = it) }; return }
        McuSetupProtocol.tone(command)?.let { reported ->
            update { copy(tone = tone.copy(bass = reported.bass, mid = reported.mid, treble = reported.treble)) }
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
