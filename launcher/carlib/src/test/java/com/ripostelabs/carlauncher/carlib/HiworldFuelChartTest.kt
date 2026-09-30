package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins 0x17 and 0x16 to the vendor parser. We used to read 0x17 p[0:1] as road speed. Stock
 * reads it as the 15-bar fuel chart (`OnHandleCanVehicleInformationPageCmd2`, TOY:447): 15
 * words `computeValue(bArr[i+1], bArr[i])` = big-endian ×0.1, the unit in `bArr[62]` = p[60].
 * The car agrees: every diag log since 2026-09-20 reads "CAN box cmd 0x17 first seen (61
 * bytes)", exactly 30 word bytes, 30 unused and the unit byte.
 *
 * 0x16 is the 5-trip history (`OnHandleCanVehicleInformationPageCmd1`, TOY:476): current trip
 * at p[0:1], trips 1..5 at p[2:11], unit at `bArr[14]` = p[12].
 */
class HiworldFuelChartTest {

    /** A 0x17 payload as the car sends it: 61 bytes, every word "no data" unless set. */
    private fun chartPayload(unit: Int, vararg words: Pair<Int, Int>): ByteArray {
        val p = ByteArray(61) { 0xFF.toByte() }
        words.forEach { (bar, v) ->
            p[bar * 2] = (v shr 8).toByte()
            p[bar * 2 + 1] = v.toByte()
        }
        p[60] = unit.toByte()
        return p
    }

    @Test
    fun `0x17 is the fuel chart, not speed`() {
        // Raw 540 is the value the 2026-08-29 drive took for 54.0 km/h.
        val sig = HiworldCanDecoder.decodePayload(0x17, chartPayload(2, 0 to 540))

        assertTrue(sig is CanSignal.FuelChart)
        val chart = sig as CanSignal.FuelChart
        assertEquals(54.0, chart.bars[0]!!, 1e-9)
        assertEquals(CanSignal.FuelUnit.L_PER_100KM, chart.unit)
    }

    @Test
    fun `fuel chart has 15 big-endian bars`() {
        // 0x0102 = 258 pins the byte order: a flipped word would read 0x0201 = 513.
        val chart = HiworldCanDecoder.decodePayload(0x17, chartPayload(1, 14 to 0x0102)) as CanSignal.FuelChart

        assertEquals(15, chart.bars.size)
        assertEquals(25.8, chart.bars[14]!!, 1e-9)
        assertEquals(CanSignal.FuelUnit.KM_PER_L, chart.unit)
    }

    @Test
    fun `fuel chart 0xFFFF bar means no data`() {
        val chart = HiworldCanDecoder.decodePayload(0x17, chartPayload(3)) as CanSignal.FuelChart

        assertTrue(chart.bars.all { it == null })
        assertEquals(CanSignal.FuelUnit.MPG_UK, chart.unit)
    }

    @Test
    fun `short fuel chart does not throw`() {
        val chart = HiworldCanDecoder.decodePayload(0x17, byteArrayOf(0x00, 0x64)) as CanSignal.FuelChart

        assertEquals(10.0, chart.bars[0]!!, 1e-9)
        assertEquals(15, chart.bars.size)
    }

    @Test
    fun `0x16 is current trip plus five past trips`() {
        val p = byteArrayOf(
            0x00, 0x3C,   // current 6.0
            0x00, 0x50,   // trip 1 8.0
            0x01, 0x02,   // trip 2 25.8
            0xFF.toByte(), 0xFF.toByte(),  // trip 3 no data
            0x00, 0x00,   // trip 4 0.0
            0x00, 0x0A,   // trip 5 1.0
            0x02,         // L/100km
        )
        val h = HiworldCanDecoder.decodePayload(0x16, p) as CanSignal.FuelHistory

        assertEquals(6.0, h.current!!, 1e-9)
        assertEquals(5, h.trips.size)
        assertEquals(8.0, h.trips[0]!!, 1e-9)
        assertEquals(25.8, h.trips[1]!!, 1e-9)
        assertNull(h.trips[2])
        assertEquals(1.0, h.trips[4]!!, 1e-9)
        assertEquals(CanSignal.FuelUnit.L_PER_100KM, h.unit)
    }
}
