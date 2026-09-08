package com.ripostelabs.carlauncher.carlib

/**
 * Accessory — something outside the car that the head unit can switch.
 *
 * Deliberately says nothing about *how* it is reached. A relay on the car network, a board on the
 * USB bus and a frame on a body bus are three completely different transports, and the thing that
 * has to be right in all three is the same: never show a state we have not confirmed.
 *
 * ── The rule this whole file exists to enforce ──────────────────────────────────────────────────
 * An accessory whose state is unknown is UNKNOWN, not off. That is the same discipline
 * [VehicleSnapshot] applies to vehicle fields — a missing source contributes nothing rather than
 * a zero — and it matters more here, because this screen has a switch on it. A toggle drawn in
 * the off position is a claim that the thing is off, and someone will act on it.
 */
data class Accessory(
    /** Stable across restarts; how a transport knows which device it is addressing. */
    val id: String,
    val name: String,
    val kind: AccessoryKind,
)

enum class AccessoryKind {
    /** On or off, nothing in between. */
    SWITCH,

    /** A 0..100 level, e.g. a dimmer or a fan. */
    LEVEL,
}

/** Desired power state. An enum rather than a boolean so a call site cannot read backwards. */
enum class Power {
    ON,
    OFF,
}

sealed interface AccessoryCommand {
    data class SetPower(val power: Power) : AccessoryCommand

    /** [level] is 0..100; a transport is responsible for rejecting anything outside its range. */
    data class SetLevel(val level: Int) : AccessoryCommand
}

/** What happened when a command was sent. Never "probably worked". */
enum class CommandResult {
    APPLIED,

    /** The accessory answered and refused — a bad level, a locked-out device. */
    REJECTED,

    /** Nothing answered. The state after this is unknown, not unchanged. */
    UNREACHABLE,
}

/**
 * What an accessory last told us about itself.
 *
 * [power] is nullable on purpose: null means "we do not know", which is a different thing from
 * [Power.OFF] and must render differently.
 */
data class AccessoryState(
    val power: Power? = null,
    val level: Int? = null,
    val atMs: Long = 0L,
) {
    /**
     * The state as of [now], or an unknown state if it has gone stale.
     *
     * A reading ages out rather than being trusted indefinitely. An accessory that has stopped
     * answering is exactly the case where a stale "on" is dangerous: the thing may have been
     * unplugged, or it may still be drawing current.
     */
    fun asOf(now: Long, staleAfter: Long = STALE_AFTER_MS): AccessoryState {
        if (power == null && level == null) {
            return this
        }

        return if (now - atMs > staleAfter) AccessoryState(atMs = atMs) else this
    }

    val isKnown: Boolean get() = power != null || level != null

    companion object {
        /**
         * Longer than the vehicle-field window: an accessory is polled, not broadcast, so its
         * updates are inherently sparser than a bus signal. Still short enough that a device
         * which has gone away stops claiming to be on while someone is looking at the screen.
         */
        const val STALE_AFTER_MS = 30_000L
    }
}
