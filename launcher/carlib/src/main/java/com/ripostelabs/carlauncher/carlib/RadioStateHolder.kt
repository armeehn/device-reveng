package com.ripostelabs.carlauncher.carlib

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the tuner last reported, as the vendor's `mRadio*` fields hold it (EventService.java:241-258).
 * [freq] is in the band's own units, FM 10 kHz / AM kHz (see [McuOwnerProtocol.RadioEvent]); 0 until
 * the MCU has said, which every screen already treats as "no tuner". [updatedAt] is 0 for the same
 * reason: [CarService] answers null from an owner that has heard nothing yet.
 *
 * [stationList] is the vendor's `mRadioFreqList` (sub 4/8), 42 slots, 0 = empty; the suite radio's
 * preset list reads it through the tuner binder (RAV4-97).
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
    val stMono: Boolean = false,
    val dxLoc: Boolean = false,
    /** Sub 0 byte 3 bit 1: the tuner is using PTY (`mRadioPTYState`). */
    val ptyEnabled: Boolean = false,
    /** Sub 0 byte 2 bit 2 and bit 3: a traffic announcement is on air; the station carries no PTY. */
    val traffic: Boolean = false,
    val noPty: Boolean = false,
    /** Sub 0 byte 3 bit 7, APS: a preset scan is running (`mRadioAPSState`, the vendor's "scanning" tip). */
    val scanning: Boolean = false,
    /** Sub 0 byte 3 bit 6, AMS: auto-store is sweeping the band (`mRadioAMSState`, "searching"). */
    val autoStoring: Boolean = false,
    /** The band plan the MCU was given at start (`SETUP_ZONE`); see [RadioZone]. */
    val zone: Int = 0,
    val stationList: List<Int> = List(McuOwnerProtocol.RADIO_FREQ_LIST_SIZE) { 0 },
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
 * no other hook.
 */
class RadioStateHolder(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUpdate: () -> Unit = {},
) {

    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    /**
     * Fill the cache before the MCU has reported, as `initRadioZone` fills `mRadioFreqList`
     * (EventService.java:6484) and [RadioMemory] remembers the last station. A report always
     * wins: once [RadioState.updatedAt] is set the seed is ignored.
     */
    fun seed(state: RadioState) {
        if (_state.value.updatedAt != 0L) {
            return
        }

        _state.value = state.copy(updatedAt = 0L)
    }

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
            stMono = event.stMono,
            dxLoc = event.loc,
            ptyEnabled = event.pty,
            traffic = event.traffic,
            noPty = event.noPty,
            scanning = event.aps,
            autoStoring = event.ams,
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
        is McuOwnerProtocol.RadioEvent.FreqList -> slot(event.index, event.freq, now)
    }

    /** A slot outside the vendor's 42 is dropped, as `onRadioFreqList` drops it. */
    private fun RadioState.slot(index: Int, freq: Int, now: Long): RadioState {
        if (index !in stationList.indices) {
            return this
        }

        return copy(stationList = stationList.toMutableList().also { it[index] = freq }, updatedAt = now)
    }
}
