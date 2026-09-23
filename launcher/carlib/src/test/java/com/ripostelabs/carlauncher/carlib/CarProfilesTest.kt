package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The car table against canbus2's sendCarTypeToCan (HiworldCanParseToyota.java) and every
 * profile's init frames against SendUtil's sum (SendUtil.java:60-85): payload sum less one.
 */
class CarProfilesTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun car(type: Int, id: Int) = CarProfiles.ALL.single { it.sysCarType == type && it.sysCarInforId == id }

    /** The unit's own sysvars: Sys_CarType 2, Sys_CarInfor_ID 9 → 0x21 (:1418). */
    @Test
    fun defaultIsThisRav4() {
        val rav4 = CarProfiles.DEFAULT

        assertEquals("RAV4", rav4.model)
        assertEquals("16-21", rav4.years)
        assertEquals(0x21, rav4.carType)
        assertEquals(listOf(0x11, 0x82, 0xF0), rav4.queries)
    }

    /** Spot rows read off the decompile, including the two jadx got wrong. */
    @Test
    fun rowsMatchTheDecompile() {
        assertEquals(0x98, car(1, 15).carType)   // Camry 17-Present, :1396 i4 = 152
        assertEquals(0x50, car(9, 1).carType)    // Hilux, :1564 i4 = 80
        assertEquals(0x6C, car(124, 6).carType)  // Crown 14th, :1738 i4 = 108
        assertEquals(0x86, car(39, 12).carType)  // Land Cruiser portrait, :1751 → i2 = 134
        assertEquals(0xB2, car(11, 9).carType)   // Sienna 11-17 (NA), bytecode only
        assertEquals(0xB9, car(11, 11).carType)  // Sienna 21-24 (NA), bytecode 185
    }

    /** Saved ids must stay unique, and 0xF0 is stock's "no match" fallback, never a real row. */
    @Test
    fun idsAreUniqueAndNoRowIsTheFallback() {
        val ids = CarProfiles.ALL.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        CarProfiles.ALL.forEach { assertNotEquals(it.label, 0xF0, it.carType) }
    }

    @Test
    fun unknownIdFallsBackToDefault() {
        assertEquals(CarProfiles.DEFAULT, CarProfiles.byId("hiworld_toyota:999:1"))
        assertEquals(CarProfiles.DEFAULT, CarProfiles.byId(null))
        assertEquals(car(1, 15), CarProfiles.byId(car(1, 15).id))
    }

    /** Camry 17-Present by hand: 02+24+98+01 = 0xBF, less one 0xBE. */
    @Test
    fun camryCarTypeFrame() {
        val inner = bytes(0x5A, 0xA5, 0x02, 0x24, 0x98, 0x01, 0xBE)

        assertArrayEquals(McuSerial.encode(0x0D, bytes(0x08) + inner), McuOwnerProtocol.canBoxCarType(car(1, 15)))
    }

    /** Every profile: queries then car type, each `5A A5 payload (sum - 1)` behind `0D 08`. */
    @Test
    fun everyProfileInitFollowsSendUtil() {
        CarProfiles.ALL.forEach { profile ->
            val payloads = profile.queries.map { intArrayOf(0x03, 0x6A, 0x05, 0x01, it) } +
                listOf(intArrayOf(0x02, 0x24, profile.carType, 0x01))
            val expected = payloads.map { p ->
                val ck = (p.sum() - 1) and 0xFF
                McuSerial.encode(0x0D, bytes(0x08, 0x5A, 0xA5, *p, ck))
            }

            val actual = McuOwnerProtocol.canBoxInit(profile)

            assertEquals(profile.label, expected.size, actual.size)
            expected.forEachIndexed { i, frame -> assertArrayEquals("${profile.label} frame $i", frame, actual[i]) }
        }
    }
}
