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
 * **`Sys_CarType` does not tell us this, and we do not pretend otherwise.** CAR_API §2.3 documents
 * the key as "Car model/type profile id" and nothing more: the value domain was never recovered,
 * because the vendor settings APK that holds the enum tables is not in the decompile (see the note
 * in [SettingKeys]). A profile id is a *model* selector — an id could plausibly imply a market and
 * therefore a steering side, but that inference has no evidence behind it and getting it wrong
 * moves every interactive control to the wrong side of a 1920px screen while the car is moving.
 *
 * So [KNOWN_RHD_CAR_TYPES] is **empty on purpose**. AUTO resolves to [DriverSide.LEFT] until a
 * value is confirmed on a real head unit, and the settings screen shows the live raw `Sys_CarType`
 * next to the override so the user can read theirs off and report it. Any entry added here is
 * GUESSED until it is confirmed against a car that is actually RHD.
 */
object Reachability {

    /**
     * Confirmed RHD `Sys_CarType` values. EMPTY — see the class KDoc. Adding a value here on a
     * hunch is the one change that must not be made without a device.
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
