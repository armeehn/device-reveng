package com.ripostelabs.carlauncher.data

/**
 * v2.8 — which side of the car the driver sits on (LAUNCHER_DESIGN §1.2, §2.5).
 *
 * Home's three columns are not symmetric: the thumb column (quick-launch + radio) is the closest
 * reach arc and holds the dense interactive set, the glance column (media + climate) is the
 * furthest and holds display-only content. That only works if the thumb column is on the driver's
 * side, so in a RHD car the two side columns swap. The centre is symmetric and never moves.
 */
enum class DriverSide { LEFT, RIGHT }

/** How [DriverSide] is chosen: inferred from the car, or pinned by the user. */
enum class DriverSideMode { AUTO, LHD, RHD }

/**
 * Resolves [DriverSideMode] against the vendor's car profile.
 *
 * **`Sys_CarType` cannot tell us this.** It is the model index within `Sys_Vehicle_deries`
 * (canbus2 `CanConstantInfo.java:497-543,645`: Toyota = 1; Camry 1, RAV4 2, Corolla 5,
 * Highlander 7, C-HR 10), so a RAV4 reads `1 / 2` on every market. Steering side is not a SysVar
 * at all: the Hiworld Toyota CAN box reports left/right rudder in its car-settings frame (0x62,
 * byte 6 bit 2, `HiworldCanParseToyota.java:672`) and canbus2 keeps it only in an in-process map
 * for its console page. Nothing on this platform exposes it to another app.
 *
 * So [KNOWN_RHD_CAR_TYPES] is **empty on purpose** and must stay so: no car-type value can ever
 * mean RHD. AUTO resolves to [DriverSide.LEFT]; the settings screen says plainly that Auto cannot
 * resolve the side here and shows the raw values so a user can confirm the profile is right.
 */
object Reachability {

    /**
     * EMPTY by design — see the class KDoc. `Sys_CarType` is a model index, not a market, so no
     * value belongs here.
     */
    private val KNOWN_RHD_CAR_TYPES = emptySet<String>()

    /** LHD is the default because it is the safe wrong answer: it matches the vendor UI's layout. */
    private val DEFAULT_SIDE = DriverSide.LEFT

    fun resolve(mode: DriverSideMode, carType: String?): DriverSide = when (mode) {
        DriverSideMode.LHD -> DriverSide.LEFT
        DriverSideMode.RHD -> DriverSide.RIGHT
        DriverSideMode.AUTO -> autoSide(carType)
    }

    private fun autoSide(carType: String?): DriverSide {
        val key = carType?.trim().orEmpty()
        if (key.isNotEmpty() && key in KNOWN_RHD_CAR_TYPES) {
            return DriverSide.RIGHT
        }
        return DEFAULT_SIDE
    }
}
