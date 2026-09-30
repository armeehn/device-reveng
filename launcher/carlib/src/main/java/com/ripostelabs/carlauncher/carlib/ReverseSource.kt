package com.ripostelabs.carlauncher.carlib

/**
 * Where the reverse line comes from before [ReverseTrigger] applies the vendor's rules.
 *
 *     MCU 71 bit 1 (wire) ──────────────┐
 *                                       ├─▶ WIRE:        wire
 *     CAN box 0x1A p[5] (gear, fresh) ──┘   WIRE_OR_CAN: wire || gear == R
 *
 * Stock calls the CAN side "protocol reverse" (`Sys_Mcu_soft_back_car_Set`, default 0 at
 * EventService.java:6585) and hands it to the MCU through the `0F` bit. We do not flip that
 * bit: what the MCU does with its wire once it is set is unknown. The launcher ORs the gear in
 * instead, so the wire always wins and the CAN side can only add a picture, never remove one.
 */
enum class ReverseSource(val setting: Int) {
    WIRE(0),
    WIRE_OR_CAN(1),
    ;

    /** The reverse line. [canGear] null means no fresh 0x1A gear ([CanGearWatch]). */
    fun line(wire: Boolean, canGear: Gear?): Boolean = when (this) {
        WIRE -> wire
        WIRE_OR_CAN -> wire || canGear == Gear.REVERSE
    }

    companion object {
        /** Stock's default: the wire only. */
        val DEFAULT = WIRE

        /** The stored setting; anything unknown is the stock default. */
        fun of(setting: Int): ReverseSource = entries.firstOrNull { it.setting == setting } ?: DEFAULT
    }
}

/**
 * The CAN box's last gear and when it arrived. 0x1A carries the RPM mirror too, so the box
 * repeats it while the engine runs; a gear older than [STALE_MS] is unknown, so a box that goes
 * quiet while in R does not hold the picture up.
 */
class CanGearWatch {

    @Volatile private var last: Gear? = null
    @Volatile private var atMs: Long = 0

    fun onGear(gear: Gear, atMs: Long) {
        this.atMs = atMs
        last = gear
    }

    /** The gear if it is fresh at [nowMs], else null. */
    fun gear(nowMs: Long): Gear? {
        if (nowMs - atMs >= STALE_MS) {
            return null
        }
        return last
    }

    companion object {
        /** Assumed: the box repeats 0x1A well inside this. Not yet measured on the car. */
        const val STALE_MS = 3_000L
    }
}
