package com.ripostelabs.carlauncher.carlib

import com.szchoiceway.canbus.CarAirState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClimateState.from] maps the vendor [CarAirState] onto the launcher's view. These tests pin
 * that mapping field by field, plus the label rules the card relies on: "Off" with the power
 * off (the vendor blanks the temperatures then) and "--" when the string is empty.
 */
class ClimateStateTest {

    @Test
    fun defaultIsInvalid() {
        val state = ClimateState()

        assertFalse(state.valid)
        assertEquals("Off", state.leftTempLabel())
    }

    @Test
    fun fromIsValid() {
        assertTrue(ClimateState.from(CarAirState()).valid)
    }

    @Test
    fun fromMapsFlags() {
        val air = CarAirState().apply {
            bAirOn = true
            bAcOn = true
            bAcMax = true
            bDualOn = true
            bOutCircleOn = true
            bMaxFrontOn = true
            bRearOn = true
            bRearLock = true
        }

        val state = ClimateState.from(air)

        assertTrue(state.powerOn)
        assertTrue(state.acOn)
        assertTrue(state.acMax)
        assertTrue(state.dualOn)
        assertTrue(state.outsideAir)
        assertTrue(state.frontDefrost)
        assertTrue(state.rearDefrost)
        assertTrue(state.rearLock)
        assertFalse(state.autoOn)
    }

    @Test
    fun autoFollowsEitherAutoBit() {
        // The RAV4 decoder sets bSmallAutoOn; other vendors set bBigAutoOn. Either is AUTO.
        assertTrue(ClimateState.from(CarAirState().apply { bSmallAutoOn = true }).autoOn)
        assertTrue(ClimateState.from(CarAirState().apply { bBigAutoOn = true }).autoOn)
        assertFalse(ClimateState.from(CarAirState()).autoOn)
    }

    @Test
    fun fromMapsModeBits() {
        val air = CarAirState().apply {
            bFunDirectHead = true
            bFunDirectFoot = true
        }

        val state = ClimateState.from(air)

        assertTrue(state.modeHead)
        assertFalse(state.modeLevel)
        assertTrue(state.modeFoot)
    }

    @Test
    fun fromMapsFan() {
        val air = CarAirState().apply {
            byFunStrength = 4
            byMaxFunStrengthStall = 7
        }

        val state = ClimateState.from(air)

        assertEquals(4, state.fanLevel)
        assertEquals(7, state.fanMax)
    }

    @Test
    fun fromMapsTempStrings() {
        val air = CarAirState().apply {
            bAirOn = true
            m_byLeftTemp = "21.5℃"
            m_byRighTemp = "HI"
            m_byTempUnit = 0
        }

        val state = ClimateState.from(air)

        assertEquals("21.5℃", state.leftTemp)
        assertEquals("HI", state.rightTemp)
        assertEquals("21.5℃", state.leftTempLabel())
        assertEquals("HI", state.rightTempLabel())
        assertEquals(ClimateState.TempUnit.CELSIUS, state.tempUnit)
    }

    @Test
    fun fromMapsFahrenheitUnit() {
        val state = ClimateState.from(CarAirState().apply { m_byTempUnit = 1 })

        assertEquals(ClimateState.TempUnit.FAHRENHEIT, state.tempUnit)
    }

    @Test
    fun nullTempStringBecomesEmpty() {
        val state = ClimateState.from(CarAirState().apply { m_byLeftTemp = null })

        assertEquals("", state.leftTemp)
    }

    @Test
    fun fromMapsSeats() {
        val air = CarAirState().apply {
            bLeftSeatHotLevel = 2
            bRightSeatHotLevel = 3
            byLeftColdLevel = 1
            byRightColdLevel = 2
        }

        val state = ClimateState.from(air)

        assertEquals(2, state.leftSeatHeat)
        assertEquals(3, state.rightSeatHeat)
        assertEquals(1, state.leftSeatCool)
        assertEquals(2, state.rightSeatCool)
    }

    @Test
    fun labelIsOffWhenPowerOff() {
        // The vendor blanks the temperature strings while bAirOn is false; say so, not "--".
        val air = CarAirState().apply {
            bAirOn = false
            m_byLeftTemp = ""
        }

        assertEquals("Off", ClimateState.from(air).leftTempLabel())
    }

    @Test
    fun labelIsDashWhenPoweredButBlank() {
        val air = CarAirState().apply {
            bAirOn = true
            m_byLeftTemp = ""
            m_byRighTemp = "  "
        }

        val state = ClimateState.from(air)

        assertEquals("--", state.leftTempLabel())
        assertEquals("--", state.rightTempLabel())
    }

    @Test
    fun leftAndRightAreSeparate() {
        // Dual-zone: a shared field would make the passenger's dial follow the driver's.
        val air = CarAirState().apply {
            bAirOn = true
            m_byLeftTemp = "22.0℃"
            m_byRighTemp = "18.0℃"
        }

        val state = ClimateState.from(air)

        assertEquals("22.0℃", state.leftTempLabel())
        assertEquals("18.0℃", state.rightTempLabel())
    }
}
