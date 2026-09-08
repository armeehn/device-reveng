package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Field

/**
 * Turns a [VehicleSnapshot] into the tiles a vehicle screen draws.
 *
 * Deliberately pure and in carlib rather than inside a composable. Every decode bug this project
 * has shipped survived review because the logic sat somewhere it could only be checked by looking
 * at a screen in a car. What decides whether a tile appears, and what text it carries, is testable
 * here without an emulator, a car or a screenshot.
 *
 * ── What is NOT here, on purpose ────────────────────────────────────────────────────────────────
 * No thresholds. Nothing in this file decides that a tyre is "low" or a coolant temperature is
 * "high", because nobody has established those numbers for this vehicle and a warning invented
 * from a plausible-looking constant is worse than no warning: it teaches the driver to ignore it.
 * [Emphasis.ALERT] is used only for things the car itself reports as a discrete state — an opening
 * ajar, reverse engaged — never for a value compared against a guess.
 */
object VehicleTiles {

    enum class Emphasis { NORMAL, ALERT }

    data class Tile(val label: String, val value: String, val emphasis: Emphasis = Emphasis.NORMAL)

    /** Door bits, as decoded from cmd 0x11. Kept beside the labels so the two cannot drift. */
    private val OPENINGS = listOf(
        0x40 to "driver",
        0x80 to "passenger",
        0x10 to "rear left",
        0x20 to "rear right",
        0x08 to "tailgate",
        0x04 to "bonnet",
    )

    /**
     * The tiles to draw, in reading order. A field with no live source contributes NOTHING —
     * it does not appear as a dash or a zero, it is simply absent, which is the project rule.
     *
     * An empty list is a legitimate answer and means there is no car to talk to: on an emulator,
     * with the engine off, or with the MCU link down. A caller must render that as "no vehicle
     * data" rather than as an empty dashboard pretending everything reads zero.
     */
    fun tilesFor(s: VehicleSnapshot, now: Long = s.atMs): List<Tile> {
        val out = mutableListOf<Tile>()

        s.int(Field.RPM, now)?.let { out += Tile("Engine", "$it rpm") }
        s.int(Field.COOLANT_C, now)?.let { out += Tile("Coolant", "$it°C") }
        s.int(Field.HYBRID_BATTERY, now)?.let { out += Tile("Hybrid battery", "$it/15") }
        s.int(Field.RANGE_KM, now)?.let { out += Tile("Range", "$it km") }

        openingsAjar(s, now)?.let { out += Tile("Open", it, Emphasis.ALERT) }

        if (s.bool(Field.REVERSE, now) == true) {
            // Labelled raw because the decoder does not apply the vendor's ACC gate; the two can
            // legitimately disagree with the ignition off, and the tile should not overclaim.
            out += Tile("Reverse", "engaged (raw)", Emphasis.ALERT)
        }

        tyreSummary(s, now)?.let { out += Tile("Tyres", it) }
        s.nearestObjectCm?.takeIf { s.raw(Field.RADAR_REAR_MIN_CM, now) != null ||
                                    s.raw(Field.RADAR_FRONT_MIN_CM, now) != null }
            ?.let { out += Tile("Nearest object", "$it cm") }

        climateSummary(s, now)?.let { out += Tile("Climate", it) }

        return out
    }

    /** Names of every opening the car reports ajar, or null when none are — never "0 open". */
    private fun openingsAjar(s: VehicleSnapshot, now: Long): String? {
        val bits = s.int(Field.DOOR_BITS, now) ?: return null
        val open = OPENINGS.filter { (mask, _) -> bits and mask != 0 }.map { it.second }
        return if (open.isEmpty()) null else open.joinToString(", ")
    }

    /**
     * Tyres as one line, preserving gaps. A wheel whose sensor is asleep shows a dash in place,
     * so the reader can tell "no reading from the rear left" from "the rear left is fine".
     */
    private fun tyreSummary(s: VehicleSnapshot, now: Long): String? {
        val vals = listOf(Field.TYRE_FL_KPA, Field.TYRE_FR_KPA, Field.TYRE_RL_KPA, Field.TYRE_RR_KPA)
            .map { s.int(it, now) }
        if (vals.all { it == null }) {
            return null
        }
        return vals.joinToString(" / ") { it?.toString() ?: "–" } + " kPa"
    }

    /**
     * Climate carries an explicit caveat because the decode is UNVERIFIED against a real vehicle:
     * it was read from the vendor parser, tested only against synthetic payloads, and the owner
     * reported it does not track the physical controls. Showing it unlabelled would present a
     * known-doubtful reading as fact.
     */
    private fun climateSummary(s: VehicleSnapshot, now: Long): String? {
        val on = s.bool(Field.CLIMATE_ON, now) ?: return null
        if (!on) {
            return "off (unverified)"
        }
        val fan = s.int(Field.FAN_STEP, now)
        val left = s.raw(Field.TEMP_LEFT_C, now)
        val parts = listOfNotNull(
            left?.let { "%.1f°C".format(it) },
            fan?.takeIf { it > 0 }?.let { "fan $it" },
        )
        return (if (parts.isEmpty()) "on" else parts.joinToString(", ")) + " (unverified)"
    }
}
