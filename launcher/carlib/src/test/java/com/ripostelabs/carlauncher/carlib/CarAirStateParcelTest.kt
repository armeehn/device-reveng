package com.ripostelabs.carlauncher.carlib

import com.szchoiceway.canbus.CarAirState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarAirState] exists only to be unparcelled from the vendor's broadcast, so what these tests
 * pin is the wire contract: the field KIND sequence the vendor `writeToParcel` produces
 * (`canbus/CarAirState.java`), that a parcel in that order lands in the right named fields, and
 * that our own write/read pair is symmetric. No Robolectric: the class routes every field through
 * [CarAirState.FieldSink]/[CarAirState.FieldSource], so a list stands in for the Parcel.
 */
class CarAirStateParcelTest {

    /** Records kinds on write, replays values on read. */
    private class FakeParcel : CarAirState.FieldSink, CarAirState.FieldSource {
        val kinds = StringBuilder()
        val values = ArrayDeque<Any?>()

        override fun bool(value: Boolean) { kinds.append('B'); values.addLast(value) }
        override fun int(value: Int) { kinds.append('I'); values.addLast(value) }
        override fun str(value: String?) { kinds.append('S'); values.addLast(value) }

        override fun bool(): Boolean = values.removeFirst() as Boolean
        override fun int(): Int = values.removeFirst() as Int
        override fun str(): String? = values.removeFirst() as String?
    }

    /**
     * Vendor `writeToParcel`, one letter per call: 12 booleans, fan int, two temp strings, unit
     * int, 2 booleans, 2 seat-cool ints, AQS boolean, 3 ints (seat heat x2, max fan), 8 booleans,
     * 2 knob ints, 2 booleans. 36 fields.
     */
    private val vendorOrder =
        "BBBBBBBBBBBB" + "I" + "SS" + "I" + "BB" + "II" + "B" + "III" + "BBBBBBBB" + "II" + "BB"

    @Test
    fun writeOrderMatchesVendor() {
        val parcel = FakeParcel()

        CarAirState().writeFields(parcel)

        assertEquals(36, vendorOrder.length)
        assertEquals(vendorOrder, parcel.kinds.toString())
    }

    @Test
    fun readConsumesExactlyTheVendorOrder() {
        // A parcel holding one value per vendor field, all of the right kind, must be drained
        // completely. Any extra or missing read would mis-align every field after it.
        val parcel = FakeParcel()
        vendorOrder.forEach { kind ->
            when (kind) {
                'B' -> parcel.bool(false)
                'I' -> parcel.int(0)
                else -> parcel.str("")
            }
        }

        CarAirState().readFields(parcel)

        assertTrue(parcel.values.isEmpty())
    }

    @Test
    fun vendorParcelLandsInNamedFields() {
        // Hand-written in the vendor's order (canbus/CarAirState.java writeToParcel).
        val parcel = FakeParcel()
        parcel.bool(true) // bAirOn
        parcel.bool(true) // bAcOn
        parcel.bool(false) // bOutCircleOn
        parcel.bool(false) // bBigAutoOn
        parcel.bool(true) // bSmallAutoOn
        parcel.bool(true) // bDualOn
        parcel.bool(false) // bMaxFrontOn
        parcel.bool(true) // bRearOn
        parcel.bool(true) // bFunDirectHead
        parcel.bool(false) // bFunDirectLevel
        parcel.bool(true) // bFunDirectFoot
        parcel.bool(false) // bAcMax
        parcel.int(5) // byFunStrength
        parcel.str("22.5℃") // m_byLeftTemp
        parcel.str("LO") // m_byRighTemp
        parcel.int(1) // m_byTempUnit
        parcel.bool(true) // bRearLock
        parcel.bool(false) // bHuaFenOn
        parcel.int(2) // byLeftColdLevel
        parcel.int(3) // byRightColdLevel
        parcel.bool(false) // bAQSInCircle
        parcel.int(1) // bLeftSeatHotLevel
        parcel.int(2) // bRightSeatHotLevel
        parcel.int(7) // byMaxFunStrengthStall
        repeat(8) { parcel.bool(false) }
        parcel.int(0) // byLeftKnobsAdjustMode
        parcel.int(0) // byRightKnobsAdjustMode
        parcel.bool(false) // bQuickCooling
        parcel.bool(false) // bQuickHeating

        val air = CarAirState().apply { readFields(parcel) }

        assertTrue(air.bAirOn)
        assertTrue(air.bAcOn)
        assertFalse(air.bOutCircleOn)
        assertTrue(air.bSmallAutoOn)
        assertTrue(air.bDualOn)
        assertTrue(air.bRearOn)
        assertTrue(air.bFunDirectHead)
        assertFalse(air.bFunDirectLevel)
        assertTrue(air.bFunDirectFoot)
        assertEquals(5, air.byFunStrength)
        assertEquals("22.5℃", air.m_byLeftTemp)
        assertEquals("LO", air.m_byRighTemp)
        assertEquals(1, air.m_byTempUnit)
        assertTrue(air.bRearLock)
        assertEquals(2, air.byLeftColdLevel)
        assertEquals(3, air.byRightColdLevel)
        assertEquals(1, air.bLeftSeatHotLevel)
        assertEquals(2, air.bRightSeatHotLevel)
        assertEquals(7, air.byMaxFunStrengthStall)
    }

    @Test
    fun roundTripKeepsEveryField() {
        val sent = CarAirState().apply {
            bAirOn = true
            bAcOn = true
            bOutCircleOn = true
            bBigAutoOn = true
            bSmallAutoOn = true
            bDualOn = true
            bMaxFrontOn = true
            bRearOn = true
            bFunDirectHead = true
            bFunDirectLevel = true
            bFunDirectFoot = true
            bAcMax = true
            byFunStrength = 3
            m_byLeftTemp = "HI"
            m_byRighTemp = "18.0℃"
            m_byTempUnit = 1
            bRearLock = true
            bHuaFenOn = true
            byLeftColdLevel = 1
            byRightColdLevel = 2
            bAQSInCircle = true
            bLeftSeatHotLevel = 3
            bRightSeatHotLevel = 1
            byMaxFunStrengthStall = 9
            bFunStrengthAuto = true
            bSmallAutoHide = true
            bDualHide = true
            bFrontHotOn = true
            bNanoeOn = true
            bFanAuto = true
            bFanDirection = true
            bZone = true
            byLeftKnobsAdjustMode = 4
            byRightKnobsAdjustMode = 5
            bQuickCooling = true
            bQuickHeating = true
        }
        val parcel = FakeParcel()

        sent.writeFields(parcel)
        val got = CarAirState().apply { readFields(parcel) }

        assertTrue(got.bAirOn && got.bAcOn && got.bOutCircleOn && got.bBigAutoOn)
        assertTrue(got.bSmallAutoOn && got.bDualOn && got.bMaxFrontOn && got.bRearOn)
        assertTrue(got.bFunDirectHead && got.bFunDirectLevel && got.bFunDirectFoot && got.bAcMax)
        assertEquals(3, got.byFunStrength)
        assertEquals("HI", got.m_byLeftTemp)
        assertEquals("18.0℃", got.m_byRighTemp)
        assertEquals(1, got.m_byTempUnit)
        assertTrue(got.bRearLock && got.bHuaFenOn && got.bAQSInCircle)
        assertEquals(1, got.byLeftColdLevel)
        assertEquals(2, got.byRightColdLevel)
        assertEquals(3, got.bLeftSeatHotLevel)
        assertEquals(1, got.bRightSeatHotLevel)
        assertEquals(9, got.byMaxFunStrengthStall)
        assertTrue(got.bFunStrengthAuto && got.bSmallAutoHide && got.bDualHide && got.bFrontHotOn)
        assertTrue(got.bNanoeOn && got.bFanAuto && got.bFanDirection && got.bZone)
        assertEquals(4, got.byLeftKnobsAdjustMode)
        assertEquals(5, got.byRightKnobsAdjustMode)
        assertTrue(got.bQuickCooling && got.bQuickHeating)
    }

    @Test
    fun defaultsMatchVendor() {
        val air = CarAirState()

        assertEquals(CarAirState.DEFAULT_MAX_FAN, air.byMaxFunStrengthStall)
        assertEquals("", air.m_byLeftTemp)
        assertEquals("", air.m_byRighTemp)
        assertFalse(air.bAirOn)
    }
}
