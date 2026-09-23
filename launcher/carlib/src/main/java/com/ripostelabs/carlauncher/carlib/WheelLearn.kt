package com.ripostelabs.carlauncher.carlib

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The stock resistive-wheel learn handshake, driven from our own Settings on Riposte OS 0.2.
 *
 * The vendor learn app (`com.szchoiceway.learn.key`, `view/CarWheelView.java`) talks to the
 * MCU through `sendWheelKey(n)` = frame `07 n` (EventService.java:6369-6375) and listens for
 * two answers: the `74` capture echo (`onCmdWheelEvent`, `:2847-2859`) and the `88` mask of
 * slots the MCU holds (`OnCmdWheelState`, `:3060-3068`). The MCU only knows slots; which
 * function a slot means is ours to keep ([WheelKeyMap], persisted through [onMap]).
 *
 * ```
 *  enter ──▶ 07 70                      MCU listens
 *  learn(f) ─▶ 07 <slot>                 lowest free slot 0..14 (CarWheelView.java:81-99)
 *            ◀─ 74 slot ok ? volt        ok != 0: slot ← f; ok == 0: nothing (:176-183)
 *            ◀─ 88 hi lo                 mask of learned slots; prunes ours (:225-240)
 *  save ────▶ 07 72                      table written to the MCU (:337)
 *  exit ────▶ 07 72, 07 71               save, then leave learn mode (:370-373)
 *  clear ───▶ 07 73                      MCU table and our map emptied (:341-349)
 * ```
 *
 * Single-threaded by contract: the owner's reader thread calls [onWheelKey] / [onWheelState]
 * and the UI calls the verbs; [state] is a StateFlow so the screen re-reads on every change.
 * UNVERIFIED on the car: that the `74` echo's slot byte is the taught slot (the vendor never
 * compares them) and whether the MCU sends `88` unprompted.
 */
class WheelLearn(
    private val send: (ByteArray) -> Unit,
    private val onMap: (WheelKeyMap) -> Unit,
    initial: WheelKeyMap,
) : McuOwner.Listener {

    enum class Phase {
        /** Not in learn mode; wheel presses are ordinary keys. */
        IDLE,
        /** `07 70` sent; waiting for a function to be picked. */
        ARMED,
        /** A slot was taught; the next `74` decides it. */
        WAITING,
    }

    /** `07 74` / `07 75`; the vendor also flips bit 2 of `Sys_McuSet` (`:314-327`), not mirrored here. */
    enum class Impedance(val code: Int) {
        HIGH(WheelKeyMap.LEARN_HIGH_IMPEDANCE),
        LOW(WheelKeyMap.LEARN_LOW_IMPEDANCE),
    }

    sealed interface Result {
        data class Learned(val slot: Int, val function: WheelFunction, val voltage: Int) : Result
        data class Failed(val slot: Int, val function: WheelFunction) : Result
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val map: WheelKeyMap = WheelKeyMap.EMPTY,
        /** The MCU's own view, bit n = slot n learned; 0 until the first `88`. */
        val learnedMask: Int = 0,
        val pending: Pair<Int, WheelFunction>? = null,
        val lastResult: Result? = null,
    )

    private val lock = Any()
    private val _state = MutableStateFlow(State(map = initial))
    val state: StateFlow<State> = _state

    /** The persisted map, when the store delivers it (DataStore loads after construction). */
    fun load(map: WheelKeyMap) {
        update { it.copy(map = map) }
    }

    /** `enterStudyMode` (`CarWheelView.java:366-368`). */
    fun enter() {
        send(McuOwnerProtocol.wheelLearn(WheelKeyMap.LEARN_ENTER))
        update { it.copy(phase = Phase.ARMED, pending = null, lastResult = null) }
    }

    /**
     * Teach the next wheel press as [function]. False when not armed or when the function is
     * already learned (the vendor ignores that click, `:74-77`). The slot is the lowest one no
     * function holds, slot 0 when all fifteen are taken (`:96-99`).
     */
    fun learn(function: WheelFunction): Boolean {
        val slot = synchronized(lock) {
            val s = _state.value
            if (s.phase == Phase.IDLE || s.map.slotOf(function) != null) {
                return false
            }
            val slot = s.map.lowestFreeSlot() ?: WheelKeyMap.SLOT_MIN
            _state.value = s.copy(phase = Phase.WAITING, pending = slot to function, lastResult = null)
            slot
        }
        send(McuOwnerProtocol.wheelLearn(slot))
        return true
    }

    /** Write the table to the MCU (`:337`). */
    fun save() {
        send(McuOwnerProtocol.wheelLearn(WheelKeyMap.LEARN_SAVE))
    }

    /** `exitStudyMode`: save, then leave (`:370-373`). */
    fun exit() {
        send(McuOwnerProtocol.wheelLearn(WheelKeyMap.LEARN_SAVE))
        send(McuOwnerProtocol.wheelLearn(WheelKeyMap.LEARN_EXIT))
        update { it.copy(phase = Phase.IDLE, pending = null) }
    }

    /** Empty the MCU table and our map (`:341-349`). */
    fun clear() {
        send(McuOwnerProtocol.wheelLearn(WheelKeyMap.LEARN_CLEAR))
        val next = update { it.copy(map = WheelKeyMap.EMPTY, pending = null, lastResult = null) }
        onMap(next.map)
    }

    fun setImpedance(impedance: Impedance) {
        send(McuOwnerProtocol.wheelLearn(impedance.code))
    }

    /** The `74` echo of a taught slot: non-zero state is a capture, zero a miss (`:176-183`). */
    override fun onWheelKey(key: McuOwnerProtocol.WheelKey) {
        val next = synchronized(lock) {
            val s = _state.value
            val (slot, function) = s.pending ?: return
            if (s.phase != Phase.WAITING) {
                return
            }

            // The vendor trusts its own slot, never the echoed one; the echo is what we show.
            if (!key.down) {
                _state.value = s.copy(phase = Phase.ARMED, pending = null, lastResult = Result.Failed(slot, function))
                return
            }
            s.copy(
                phase = Phase.ARMED,
                pending = null,
                map = s.map.with(slot, function),
                lastResult = Result.Learned(key.slot, function, key.voltage),
            ).also { _state.value = it }
        }
        onMap(next.map)
    }

    /** The `88` mask is the MCU's truth: functions on slots it does not hold are dropped (`:225-240`). */
    override fun onWheelState(mask: Int) {
        val next = update { it.copy(learnedMask = mask, map = it.map.retain(mask)) }
        onMap(next.map)
    }

    private fun update(change: (State) -> State): State = synchronized(lock) {
        _state.value = change(_state.value)
        _state.value
    }

    companion object {
        /** Full scale of the MCU's 8-bit reading (`CarWheelView.java:162`). */
        private const val ADC_MAX = 255f
        private const val ADC_REF_VOLTS = 3.3f

        /** The wheel voltage behind a `74` byte 4, as the vendor page prints it. */
        fun volts(raw: Int): Float = raw / ADC_MAX * ADC_REF_VOLTS
    }
}
