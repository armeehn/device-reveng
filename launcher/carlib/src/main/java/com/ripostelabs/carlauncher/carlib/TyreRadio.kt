package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.CarEvents.Motion
import org.json.JSONObject

/**
 * Tyre sensors heard directly over the air, without the CAN box: an RTL-SDR on the unit's USB
 * runs rtl_433, and each JSON line it prints becomes a [TyreReading].
 *
 *     sensor ──315 MHz FSK──▶ RTL-SDR ──rtl_433 -F json──▶ [TyreRadioParser] ──▶ [TyreSensorSet]
 *
 * A Toyota sensor carries an id, a pressure and a temperature, but no wheel position, and every
 * car nearby is heard too. [TyreSensorSet] learns which ids belong to this car.
 */
data class TyreReading(
    val id: String,
    val model: String,
    val kpa: Double,
    val tempC: Double?,
    val atMs: Long,
) {
    val psi: Double get() = kpa / TyreRadioParser.KPA_PER_PSI
}

/** One rtl_433 `-F json` line to a [TyreReading]; anything that is not a tyre sensor is null. */
object TyreRadioParser {

    const val KPA_PER_PSI = 6.894757
    private const val KPA_PER_BAR = 100.0
    private const val TYPE_TPMS = "TPMS"

    fun parse(line: String, atMs: Long): TyreReading? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (json.optString("type") != TYPE_TPMS) {
            return null
        }

        // Ids are hex strings for most decoders, numbers for a few.
        val id = json.opt("id")?.toString().orEmpty()
        if (id.isEmpty()) {
            return null
        }
        val kpa = pressureKpa(json) ?: return null
        val tempC = if (json.has("temperature_C")) json.optDouble("temperature_C") else null

        return TyreReading(id, json.optString("model"), kpa, tempC, atMs)
    }

    /** Decoders report in their sensor's own unit: Toyota in PSI, others in kPa or bar. */
    private fun pressureKpa(json: JSONObject): Double? = when {
        json.has("pressure_kPa") -> json.optDouble("pressure_kPa")
        json.has("pressure_PSI") -> json.optDouble("pressure_PSI") * KPA_PER_PSI
        json.has("pressure_bar") -> json.optDouble("pressure_bar") * KPA_PER_BAR
        else -> null
    }
}

/**
 * Which sensors are this car's, and their latest readings.
 *
 * Own sensors ride along: a sensor heard in [OWN_MINUTES] separate minutes within [WINDOW_MS]
 * while not parked is ours. On the 2026-10-02 drive the car's four scored 5 to 6 minutes in six;
 * the neighbour that followed longest scored 2. Parked readings never teach, so a car beside us
 * in a car park does not join. A sensor unheard for [FORGET_MS] is dropped (tyre swap), and at
 * most [MAX_OWN] are kept (four wheels and a spare), the least recently heard going first.
 *
 * [lastHeard] (id to last heard ms) is what the caller persists; pass it back to the constructor.
 */
class TyreSensorSet(lastHeard: Map<String, Long> = emptyMap()) {

    private val own = LinkedHashMap(lastHeard)
    private val minutes = HashMap<String, ArrayDeque<Long>>()
    private val latest = HashMap<String, TyreReading>()

    val lastHeard: Map<String, Long> get() = own.toMap()

    fun ownIds(): Set<String> = own.keys.toSet()

    /** Latest reading of each own sensor that has been heard since start. */
    fun own(): List<TyreReading> = own.keys.mapNotNull { latest[it] }

    /** @return true when the own set changed, so the caller saves [lastHeard]. */
    fun record(reading: TyreReading, motion: Motion): Boolean {
        latest[reading.id] = reading
        var changed = forget(reading.atMs)

        // A known sensor: refresh it. Saved only on set changes, so a reboot loses at most a drive.
        if (reading.id in own) {
            own[reading.id] = reading.atMs
            return changed
        }

        if (motion == Motion.PARKED) {
            return changed
        }

        // Count separate minutes inside the window.
        val minute = reading.atMs / MINUTE_MS
        val heard = minutes.getOrPut(reading.id) { ArrayDeque() }
        if (heard.lastOrNull() != minute) {
            heard.addLast(minute)
        }
        while (heard.isNotEmpty() && heard.first() < minute - WINDOW_MS / MINUTE_MS) {
            heard.removeFirst()
        }
        if (heard.size < OWN_MINUTES) {
            return changed
        }

        own[reading.id] = reading.atMs
        minutes.remove(reading.id)
        while (own.size > MAX_OWN) {
            own.remove(own.minByOrNull { it.value }!!.key)
        }
        changed = true
        return changed
    }

    private fun forget(nowMs: Long): Boolean {
        val stale = own.filterValues { nowMs - it > FORGET_MS }.keys
        stale.forEach { own.remove(it) }
        return stale.isNotEmpty()
    }

    private companion object {
        const val OWN_MINUTES = 4
        const val MINUTE_MS = 60_000L
        const val WINDOW_MS = 20 * MINUTE_MS
        const val FORGET_MS = 7 * 24 * 60 * MINUTE_MS
        const val MAX_OWN = 5
    }
}

enum class TyreState { OK, SOFT, LOW }

/**
 * Two checks, because the car's own warning comes late:
 *  - LOW: 25 % under the door placard, the FMVSS/CMVSS 138 warning point.
 *  - SOFT: [SOFT_GAP_KPA] under the median of the other tyres. One tyre leaking shows here long
 *    before LOW, and the comparison cancels temperature, which moves all four together.
 *    Example: 27.5 psi against 31.75 on the other three (2026-10-02) is SOFT.
 */
object TyreHealth {

    /** RAV4 (XA50) door placard: 35 psi cold, front and rear. */
    const val PLACARD_KPA = 241.0
    private const val LOW_FRACTION = 0.75
    private const val SOFT_GAP_KPA = 20.0

    fun of(tyre: TyreReading, others: List<TyreReading>): TyreState {
        if (tyre.kpa < PLACARD_KPA * LOW_FRACTION) {
            return TyreState.LOW
        }
        if (others.isEmpty()) {
            return TyreState.OK
        }

        val sorted = others.map { it.kpa }.sorted()
        val median = sorted[sorted.size / 2]
        return if (median - tyre.kpa >= SOFT_GAP_KPA) TyreState.SOFT else TyreState.OK
    }
}
