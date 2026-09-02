package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Reachability] decides which side of a 1920px screen every interactive control lands on, so a
 * wrong answer is not cosmetic: it moves the thumb column out of reach while the car is moving.
 *
 * The AUTO branch is deliberately inert. `Sys_CarType` is a model index (RAV4 = 2), not a market,
 * and the steering side never leaves the CAN box, so the known-RHD table is empty and AUTO must
 * answer LEFT for every input. These tests pin that emptiness down — adding a car type to the
 * table breaks [autoIsLeftForAnyCarType], which is the point.
 */
class ReachabilityTest {

    @Test
    fun pinnedModesIgnoreTheCarType() {
        // LHD/RHD are the driver's explicit override. No vendor value may talk them out of it.
        listOf(null, "", "  ", "12", "unknown-profile").forEach { carType ->
            assertEquals(DriverSide.LEFT, Reachability.resolve(DriverSideMode.LHD, carType))
            assertEquals(DriverSide.RIGHT, Reachability.resolve(DriverSideMode.RHD, carType))
        }
    }

    @Test
    fun autoIsLeftForAnyCarType() {
        listOf("0", "1", "2", "42", "RHD", "rhd", "  7  ", "Sys_CarType").forEach { carType ->
            assertEquals(
                "AUTO resolved RIGHT for car type '$carType' — the known-RHD table is meant to be empty",
                DriverSide.LEFT,
                Reachability.resolve(DriverSideMode.AUTO, carType),
            )
        }
    }

    @Test
    fun autoSurvivesAnAbsentCarType() {
        // SysVar.getString returns null when the vendor provider is missing, and a settings read of
        // an unset key yields an empty string. Neither may throw on the way to a layout decision.
        assertEquals(DriverSide.LEFT, Reachability.resolve(DriverSideMode.AUTO, null))
        assertEquals(DriverSide.LEFT, Reachability.resolve(DriverSideMode.AUTO, ""))
        assertEquals(DriverSide.LEFT, Reachability.resolve(DriverSideMode.AUTO, "   "))
        // `settings get` prints the four characters "null" for an unset key — a string, not absence.
        assertEquals(DriverSide.LEFT, Reachability.resolve(DriverSideMode.AUTO, "null"))
    }

    @Test
    fun modeNamesSurvivePersistence() {
        // SettingsStore and DriverProfile both persist this enum by name. Renaming an entry resets
        // every stored preference to AUTO on the next boot, silently.
        assertEquals(
            listOf("AUTO", "LHD", "RHD"),
            DriverSideMode.values().map { it.name },
        )
    }
}
