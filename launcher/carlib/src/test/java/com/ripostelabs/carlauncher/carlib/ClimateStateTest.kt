package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClimateState.fromAirData] decodes an AIDL frame whose offsets are GUESSED (see the class
 * KDoc), so the value these tests protect is the *behaviour around* the guess: a short or absent
 * frame must stay invalid so the card shows its placeholder, an implausible frame must not be
 * promoted to valid, and the temperature heuristic must resolve each of its three branches the
 * way it is documented to.
 *
 * If a live capture corrects the layout, the offsets below change and the invalid/plausibility
 * cases should not.
 */
class ClimateStateTest {

    /** Smallest frame [ClimateState.fromAirData] accepts, with every field in range. */
    private fun goodFrame(
        flags: Int = 0x00,
        airMode: Int = 0,
        fan: Int = 3,
        leftT: Int = 22,
        rightT: Int = 20,
    ) = byteArrayOf(
        flags.toByte(), airMode.toByte(), fan.toByte(),
        leftT.toByte(), rightT.toByte(), 0,
    )

    @Test
    fun nullFrameIsInvalid() {
        val state = ClimateState.fromAirData(null)

        assertFalse(state.valid)
        assertEquals("--", state.leftTempLabel())
        assertEquals("--", state.rightTempLabel())
    }

    @Test
    fun shortFrameIsInvalid() {
        // One byte below the minimum. Decoding it would read past the fields we need.
        assertFalse(ClimateState.fromAirData(ByteArray(5)).valid)
        assertTrue(ClimateState.fromAirData(ByteArray(6)).valid)
    }

    @Test
    fun fullFrameDecodes() {
        val state = ClimateState.fromAirData(goodFrame(flags = 0x0F, airMode = 2))

        assertTrue(state.valid)
        assertTrue(state.acOn)
        assertTrue(state.autoOn)
        assertTrue(state.dualOn)
        assertTrue(state.rearAirOn)
        assertEquals(2, state.airMode)
        assertEquals(3, state.fanLevel)
        assertEquals(22, state.leftTempRaw)
        assertEquals(20, state.rightTempRaw)
    }

    @Test
    fun flagBitsAreIndependent() {
        // Each flag is its own bit in byte 0; a shared mask would light them up together.
        val state = ClimateState.fromAirData(goodFrame(flags = 0x05))

        assertTrue(state.acOn)
        assertFalse(state.autoOn)
        assertTrue(state.dualOn)
        assertFalse(state.rearAirOn)
    }

    @Test
    fun implausibleFanIsInvalid() {
        assertTrue(ClimateState.fromAirData(goodFrame(fan = 12)).valid)
        assertFalse(ClimateState.fromAirData(goodFrame(fan = 13)).valid)
        assertFalse(ClimateState.fromAirData(goodFrame(fan = 0xFF)).valid)
    }

    @Test
    fun implausibleTempIsInvalid() {
        assertTrue(ClimateState.fromAirData(goodFrame(leftT = 64)).valid)
        assertFalse(ClimateState.fromAirData(goodFrame(leftT = 65)).valid)
    }

    @Test
    fun fanLevelClampsBelowPlausible() {
        // 10 passes the plausibility gate (0..12) but the card only has 8 bars, so the exposed
        // level saturates while the frame stays valid.
        val state = ClimateState.fromAirData(goodFrame(fan = 10))

        assertTrue(state.valid)
        assertEquals(8, state.fanLevel)
    }

    @Test
    fun tempLabelPrefersDirectCelsius() {
        // 16..32 looks like a cabin set-point already, so it is shown as-is rather than being
        // put through the (16 + raw/2) decode, which would read 22 as 27°.
        assertEquals("22°", ClimateState.fromAirData(goodFrame(leftT = 22)).leftTempLabel())
        assertEquals("16°", ClimateState.fromAirData(goodFrame(leftT = 16)).leftTempLabel())
        assertEquals("32°", ClimateState.fromAirData(goodFrame(leftT = 32)).leftTempLabel())
    }

    @Test
    fun tempLabelFallsBackToHalfSteps() {
        // Below 16 the raw cannot be a direct °C, so the half-degree encoding applies.
        assertEquals("16°", ClimateState.fromAirData(goodFrame(leftT = 0)).leftTempLabel())
        assertEquals("22°", ClimateState.fromAirData(goodFrame(leftT = 12)).leftTempLabel())
    }

    @Test
    fun tempLabelRejectsNonsense() {
        // Neither reading lands in a cabin range — say nothing rather than invent a number.
        assertEquals("--", ClimateState.fromAirData(goodFrame(leftT = 33)).leftTempLabel())
        assertEquals("--", ClimateState.fromAirData(goodFrame(leftT = 64)).leftTempLabel())
        assertEquals("--", ClimateState().leftTempLabel())
    }

    @Test
    fun leftAndRightAreSeparate() {
        // Dual-zone: a shared field would make the passenger's dial follow the driver's.
        val state = ClimateState.fromAirData(goodFrame(leftT = 22, rightT = 18))

        assertEquals("22°", state.leftTempLabel())
        assertEquals("18°", state.rightTempLabel())
    }
}
