package com.ripostelabs.carlauncher.carlib

/**
 * VehicleSnapshot — every decoded vehicle signal folded into one immutable value the UI can read.
 *
 * Until now the decoded signals reached exactly one place: the CAN capture screen in settings, a
 * diagnostic surface. Nothing carried them to the launcher proper, so none of the work that went
 * into decoding them was visible while driving. This is the seam that fixes that.
 *
 * ── Every field is nullable, and that is the whole design ───────────────────────────────────────
 * Null means "no source for this right now", never "zero". The project's own rule is that an
 * indicator with no source disappears rather than lying, and a snapshot that defaults a missing
 * reading to 0 makes obeying that rule impossible for every screen downstream. A dash is honest;
 * a fabricated zero is not, and on a temperature or a tyre pressure it is dangerous.
 *
 * ── Staleness is per field, not per snapshot ────────────────────────────────────────────────────
 * The signals arrive at wildly different rates: `0x11` several times a second, TPMS only when the
 * sensors wake. One global "last updated" would either mark live data stale or keep a dead reading
 * on screen. So each field carries its own timestamp and [staleAfter] decides, per field, whether
 * it still counts. That is why [rpm] and friends are accessors rather than stored values.
 *
 * Pure Kotlin with no Android imports, so the whole thing is unit-testable off-device — which
 * matters, because the alternative is verifying vehicle logic by driving.
 */
data class VehicleSnapshot(
    /** Wall clock for the whole snapshot, supplied by the caller so this stays framework-free. */
    val atMs: Long = 0L,
    private val fields: Map<Field, Timed> = emptyMap(),
) {

    /** Every value this snapshot can hold. Adding one here forces the compiler to route it. */
    enum class Field {
        RPM, COOLANT_C, HYBRID_BATTERY, ENERGY_FLOW,
        TYRE_FL_KPA, TYRE_FR_KPA, TYRE_RL_KPA, TYRE_RR_KPA, TYRE_SPARE_KPA,
        STEERING_DEG, RANGE_KM,
        DOOR_BITS, REVERSE, SIDE_CAMERA_LEFT, SIDE_CAMERA_RIGHT,
        CLIMATE_ON, FAN_STEP, TEMP_LEFT_C, TEMP_RIGHT_C,
        RADAR_REAR_MIN_CM, RADAR_FRONT_MIN_CM,
    }

    /** One reading and when it landed. */
    data class Timed(val value: Double, val atMs: Long)

    /**
     * How long a field stays believable with no update.
     *
     * Deliberately generous and uniform rather than tuned per signal. A too-short window blanks
     * live data on a busy bus and looks like a fault; a too-long one keeps a dead reading on
     * screen, which is the failure that actually matters. Ten seconds is long enough to survive
     * the slowest signal here (TPMS, which only reports when sensors wake) and short enough that
     * an unplugged bus clears the dashboard while the driver is still looking at it.
     */
    companion object {
        const val STALE_AFTER_MS = 10_000L

        /** Fold one decoded signal into a snapshot. Unknown signals leave it untouched. */
        fun VehicleSnapshot.fold(sig: CanSignal, atMs: Long): VehicleSnapshot = when (sig) {
            is CanSignal.VehicleInfo -> put(atMs, Field.RPM to sig.rpm.toDouble())
                .let { s -> sig.coolantC?.let { s.put(atMs, Field.COOLANT_C to it.toDouble()) } ?: s }

            is CanSignal.Hybrid -> put(atMs,
                Field.HYBRID_BATTERY to sig.batteryLevel.toDouble(),
                Field.ENERGY_FLOW to sig.energyFlowRaw.toDouble())

            is CanSignal.Tpms -> putNullable(atMs,
                Field.TYRE_FL_KPA to sig.frontLeftKpa,
                Field.TYRE_FR_KPA to sig.frontRightKpa,
                Field.TYRE_RL_KPA to sig.rearLeftKpa,
                Field.TYRE_RR_KPA to sig.rearRightKpa,
                Field.TYRE_SPARE_KPA to sig.spareKpa)

            is CanSignal.BasicStatus -> put(atMs,
                Field.STEERING_DEG to sig.steerAngleDeg,
                Field.DOOR_BITS to sig.doorBits.toDouble())

            is CanSignal.SysEvent -> put(atMs, Field.REVERSE to if (sig.reverseRaw) 1.0 else 0.0)

            is CanSignal.SideCamera -> put(atMs,
                Field.SIDE_CAMERA_LEFT to if (sig.left) 1.0 else 0.0,
                Field.SIDE_CAMERA_RIGHT to if (sig.right) 1.0 else 0.0)

            is CanSignal.Climate -> put(atMs,
                Field.CLIMATE_ON to if (sig.on) 1.0 else 0.0,
                Field.FAN_STEP to sig.fanStep.toDouble())
                .let { s -> sig.leftTempC?.let { s.put(atMs, Field.TEMP_LEFT_C to it) } ?: s }
                .let { s -> sig.rightTempC?.let { s.put(atMs, Field.TEMP_RIGHT_C to it) } ?: s }

            // Nearest object only. A dashboard wants "how close is anything", and the per-sensor
            // detail already lives on the capture screen for whoever needs it.
            is CanSignal.ParkingRadar -> putNullable(atMs,
                Field.RADAR_REAR_MIN_CM to sig.rearCm.filterNotNull().minOrNull(),
                Field.RADAR_FRONT_MIN_CM to sig.frontCm.filterNotNull().minOrNull())

            is CanSignal.TripInfo -> putNullable(atMs, Field.RANGE_KM to sig.rangeToEmptyKm)

            // Speed is NOT folded in. A real drive proved 0x32 is not road speed, and 0x17/0x13
            // remain unconfirmed candidates. Putting either here would let a screen show it as
            // fact, and the motion safety gate is downstream of exactly this type.
            is CanSignal.SpeedCandidate -> this
            is CanSignal.RpmGearMirror -> this
            is CanSignal.Version -> this
            is CanSignal.Unknown -> this
        }.copy(atMs = atMs)
    }

    private fun put(atMs: Long, vararg pairs: Pair<Field, Double>): VehicleSnapshot =
        copy(fields = fields + pairs.associate { (f, v) -> f to Timed(v, atMs) })

    private fun putNullable(atMs: Long, vararg pairs: Pair<Field, Int?>): VehicleSnapshot =
        copy(fields = fields + pairs.mapNotNull { (f, v) ->
            v?.let { f to Timed(it.toDouble(), atMs) }
        }.toMap())

    /**
     * The value of [field], or null if it was never seen or has gone stale.
     *
     * [now] is passed in rather than read from a clock so staleness is testable without sleeping.
     */
    fun raw(field: Field, now: Long = atMs, staleAfter: Long = STALE_AFTER_MS): Double? {
        val t = fields[field] ?: return null
        return if (now - t.atMs > staleAfter) null else t.value
    }

    fun int(field: Field, now: Long = atMs): Int? = raw(field, now)?.toInt()
    fun bool(field: Field, now: Long = atMs): Boolean? = raw(field, now)?.let { it != 0.0 }

    val rpm: Int? get() = int(Field.RPM)
    val coolantC: Int? get() = int(Field.COOLANT_C)
    val hybridBattery: Int? get() = int(Field.HYBRID_BATTERY)
    val steeringDeg: Double? get() = raw(Field.STEERING_DEG)
    val rangeKm: Int? get() = int(Field.RANGE_KM)
    val reverse: Boolean? get() = bool(Field.REVERSE)
    val climateOn: Boolean? get() = bool(Field.CLIMATE_ON)
    val fanStep: Int? get() = int(Field.FAN_STEP)

    /** Tyres in the order a driver pictures them, nulls preserved so a dead sensor shows a dash. */
    val tyresKpa: List<Int?>
        get() = listOf(Field.TYRE_FL_KPA, Field.TYRE_FR_KPA, Field.TYRE_RL_KPA, Field.TYRE_RR_KPA)
            .map { int(it) }

    /** Nearest object in cm, either end, or null when nothing is detected or the radar is quiet. */
    val nearestObjectCm: Int?
        get() = listOfNotNull(int(Field.RADAR_REAR_MIN_CM), int(Field.RADAR_FRONT_MIN_CM)).minOrNull()

    /** True when at least one field is live, i.e. there is a car to draw at all. */
    fun hasAnySource(now: Long = atMs): Boolean = Field.entries.any { raw(it, now) != null }
}
