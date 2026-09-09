package com.ripostelabs.carlauncher.carlib

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How an accessory is actually reached. The one place that knows about wires.
 *
 * Blocking rather than suspending, matching [CanableUsbLink]: callers already run this on a worker
 * thread, and a blocking interface keeps every policy decision above it testable with plain JUnit
 * instead of a coroutine harness.
 */
interface AccessoryTransport {

    fun apply(accessory: Accessory, command: AccessoryCommand): CommandResult

    /** Ask the device what it is doing. Null when it did not answer. */
    fun read(accessory: Accessory): AccessoryState?
}

/**
 * AccessoryController — the service layer between a screen and a transport.
 *
 * Everything here exists to stop the screen from lying about hardware.
 *
 * ── Why there is no optimistic update ───────────────────────────────────────────────────────────
 * The obvious implementation flips the state as soon as the user taps, then corrects it if the
 * command fails. That is the standard bug in every accessory UI: the toggle moves, the relay does
 * not, and the screen now asserts something false about a physical thing. Here a command changes
 * state only on [CommandResult.APPLIED], and an unreachable device drops to UNKNOWN rather than
 * keeping its last value — because after a failed command we genuinely do not know what happened.
 *
 *     tap ──> transport ──> APPLIED      ──> state becomes what we asked for
 *                       ├─> REJECTED     ──> state unchanged (device answered, and said no)
 *                       └─> UNREACHABLE  ──> state becomes UNKNOWN (we cannot claim anything)
 */
class AccessoryController(
    private val transport: AccessoryTransport,
    accessories: List<Accessory> = emptyList(),
) {

    private val known = accessories.associateBy { it.id }

    private val _states = MutableStateFlow<Map<String, AccessoryState>>(emptyMap())
    val states: StateFlow<Map<String, AccessoryState>> = _states.asStateFlow()

    fun accessories(): List<Accessory> = known.values.toList()

    /** The state of [id] as of [now], with staleness applied. Unknown for anything unheard of. */
    fun state(id: String, now: Long): AccessoryState =
        (_states.value[id] ?: AccessoryState()).asOf(now)

    /**
     * Send [command] to [id] and record only what the device actually confirmed.
     *
     * Returns [CommandResult.REJECTED] for an unknown id: refusing an accessory we were never told
     * about is a rejection by us, not a claim that something out there is unreachable.
     */
    fun send(id: String, command: AccessoryCommand, now: Long): CommandResult {
        val accessory = known[id] ?: return CommandResult.REJECTED

        return when (val result = transport.apply(accessory, command)) {
            CommandResult.APPLIED -> {
                put(id, stateFor(command, now))
                result
            }

            // The device answered and refused, so whatever we last knew still holds.
            CommandResult.REJECTED -> result

            // Nothing answered. Anything we previously believed is now a guess.
            CommandResult.UNREACHABLE -> {
                put(id, AccessoryState(atMs = now))
                result
            }
        }
    }

    /** Poll [id] and store whatever it reports. A silent device becomes UNKNOWN, not stale-on. */
    fun refresh(id: String, now: Long): AccessoryState {
        val accessory = known[id] ?: return AccessoryState()

        val reported = transport.read(accessory)?.copy(atMs = now) ?: AccessoryState(atMs = now)
        put(id, reported)

        return reported
    }

    fun refreshAll(now: Long) {
        known.keys.forEach { refresh(it, now) }
    }

    /** What the accessory should be in once it confirms the command it was given. */
    private fun stateFor(command: AccessoryCommand, now: Long): AccessoryState = when (command) {
        is AccessoryCommand.SetPower -> AccessoryState(power = command.power, atMs = now)

        // A level of zero is off; anything above it is on. Recording both keeps a dimmer's tile
        // from having to guess which of the two fields to believe.
        is AccessoryCommand.SetLevel -> AccessoryState(
            power = if (command.level > 0) Power.ON else Power.OFF,
            level = command.level,
            atMs = now,
        )
    }

    /**
     * Atomic, because a poll and a command can now land at the same moment from different
     * coroutines. `value = value + entry` read-modify-writes and would let one of them vanish;
     * `update` retries on contention so both entries survive.
     */
    private fun put(id: String, state: AccessoryState) {
        _states.update { it + (id to state) }
    }
}
