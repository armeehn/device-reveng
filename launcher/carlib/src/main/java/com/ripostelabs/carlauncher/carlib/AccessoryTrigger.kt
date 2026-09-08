package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Field

/**
 * AccessoryTrigger — a vehicle event starts a sequence.
 *
 * "Reverse engaged → work lights on." "Any door opens → welcome sequence." "All doors shut →
 * stow." The condition reads the same [VehicleSnapshot] the Vehicle screen draws from, so a
 * trigger fires on what the car actually reported, on either bus.
 *
 * ── Edges, not levels ───────────────────────────────────────────────────────────────────────────
 * The snapshot is re-read twice a second. A trigger that fired whenever its condition was TRUE
 * would restart "work lights on" every 500 ms for the whole reversing manoeuvre. So a trigger
 * fires once, when its condition goes from false to true, and arms again only after it has gone
 * false. That is what a person means by "when the car goes into reverse".
 *
 * ── Unknown is not false ────────────────────────────────────────────────────────────────────────
 * A condition reads null when its field has no live source: the bus is down, or the engine is
 * off. Null never fires, and — the part that is easy to get wrong — null never ARMS either. If it
 * did, a bus dropout mid-reverse would read as "reverse ended", and the bus coming back would
 * read as "reverse began" and fire the lights a second time. Nothing happened in the car; the
 * trigger must not invent an edge from a gap in the data.
 */
class AccessoryTrigger(
    val id: String,
    val name: String,
    val sequence: AccessorySequence,

    /** True, false, or null for "cannot tell right now". */
    private val condition: (VehicleSnapshot, Long) -> Boolean?,
) {

    /** What the condition last read, ignoring nulls. Null until the first definite reading. */
    private var armedOn: Boolean? = null

    /** Whether this snapshot is a rising edge. Advances the edge detector either way. */
    fun fires(snapshot: VehicleSnapshot, now: Long): Boolean {
        val reading = condition(snapshot, now) ?: return false

        val previous = armedOn
        armedOn = reading

        // The first definite reading establishes a baseline; it is never itself an edge. A
        // launcher that starts while the car is already in reverse should not fire the lights.
        return previous == false && reading
    }

    companion object {
        /** Reverse engaged, as the raw bit reports it. */
        fun reverse(snapshot: VehicleSnapshot, now: Long): Boolean? = snapshot.bool(Field.REVERSE, now)

        /** Any opening ajar. Unknown when the door byte has no live source. */
        fun anyDoorOpen(snapshot: VehicleSnapshot, now: Long): Boolean? =
            snapshot.int(Field.DOOR_BITS, now)?.let { it and DOOR_MASK != 0 }

        /** Every opening shut. Unknown when unknown; the inverse of [anyDoorOpen], not its negation. */
        fun allDoorsShut(snapshot: VehicleSnapshot, now: Long): Boolean? =
            anyDoorOpen(snapshot, now)?.let { !it }

        /** The six openings the MCU door byte carries. Other bits are not doors. */
        private const val DOOR_MASK = 0xFC
    }
}

/**
 * Evaluates every trigger against a snapshot and returns the sequences that should start.
 *
 * Order is the registration order, so if two triggers fire on the same tick the caller sees a
 * deterministic list — the runner will run only the last one it is handed, and that should not
 * depend on hash order.
 */
class TriggerEngine(private val triggers: List<AccessoryTrigger>) {

    fun evaluate(snapshot: VehicleSnapshot, now: Long): List<AccessorySequence> =
        triggers.filter { it.fires(snapshot, now) }.map { it.sequence }

    fun triggers(): List<AccessoryTrigger> = triggers
}
