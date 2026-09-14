package com.ripostelabs.carlauncher.carlib

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the tuner last reported, as the vendor's `mRadio*` fields hold it (EventService.java:241-258).
 * [freq] is in the band's own units, FM 10 kHz / AM kHz (see [McuOwnerProtocol.RadioEvent]); 0 until
 * the MCU has said, which every screen already treats as "no tuner". [updatedAt] is 0 for the same
 * reason: [CarService] answers null from an owner that has heard nothing yet.
 */
data class RadioState(
    val band: Int = 0,
    val freq: Int = 0,
    val presetNumber: Int = 0,
    val stationName: String = "",
    val ptyNumber: Int = 0,
    val rds: Boolean = false,
    val ta: Boolean = false,
    val af: Boolean = false,
    val stereo: Boolean = false,
    val tp: Boolean = false,
    val updatedAt: Long = 0L,
)

/**
 * RadioStateHolder — the cache behind the tuner getters on Riposte OS 0.2.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     McuOwner ──▶ Listener.onRadio(RadioEvent) ──▶ RadioStateHolder ──▶ CarService.getRadioFreq() …
 *                                                        └─▶ onUpdate ──▶ CarService.radioEvents tick
 *
 * The vendor gateway does the same fold: each `73` sub-command overwrites one field and fires
 * `notifyRadioEvt` (EventService.java:2763-2845), and `getRadioFreq` and friends (:7482-7545)
 * read the field back. The tuner screen already re-polls the getters on every tick, so it needs
 * no other hook. Station-list slots (sub 4/8) are not kept: nothing in the launcher reads them.
 */
class RadioStateHolder(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUpdate: () -> Unit = {},
) {

    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    /** Called from the owner's pump thread only, so read-modify-write needs no loop. */
    fun onRadio(event: McuOwnerProtocol.RadioEvent) {
        val current = _state.value
        val next = current.apply(event, clock())
        if (next === current) {
            return
        }

        _state.value = next
        onUpdate()
    }

    private fun RadioState.apply(event: McuOwnerProtocol.RadioEvent, now: Long): RadioState = when (event) {
        is McuOwnerProtocol.RadioEvent.State -> copy(
            rds = event.rds,
            ta = event.ta,
            af = event.af,
            stereo = event.stereoIcon,
            tp = event.tpIcon,
            updatedAt = now,
        )

        is McuOwnerProtocol.RadioEvent.Band -> copy(
            band = event.band ?: band,
            presetNumber = event.preset ?: presetNumber,
            updatedAt = now,
        )

        is McuOwnerProtocol.RadioEvent.Preset -> copy(presetNumber = event.preset, updatedAt = now)
        is McuOwnerProtocol.RadioEvent.Frequency -> copy(freq = event.freq, updatedAt = now)
        is McuOwnerProtocol.RadioEvent.Pty -> copy(ptyNumber = event.pty, updatedAt = now)
        is McuOwnerProtocol.RadioEvent.StationName -> copy(stationName = event.name, updatedAt = now)
        is McuOwnerProtocol.RadioEvent.FreqList -> this
    }
}
