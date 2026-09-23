package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The band plans behind `SETUP_ZONE`: the vendor radio's `getCurrRadioFreq` bounds and steps
 * (UIControllerBase.java:117-209, landscape branch) and the preset defaults `initRadioZone`
 * loads before the MCU has reported (EventService.java:7275-7480).
 */
class RadioZoneTest {

    /** Zone 1 is North America: 200 kHz FM steps on a landscape unit, 10 kHz AM. */
    @Test
    fun northAmericaPlan() {
        val zone = RadioZone.of(1)

        assertEquals(RadioZone.Plan(min = 8750, max = 10790, step = 20), zone.fm)
        assertEquals(RadioZone.Plan(min = 530, max = 1710, step = 10), zone.am)
    }

    /** Zone 0 is Europe: 50 kHz FM steps, 9 kHz AM. */
    @Test
    fun europePlan() {
        val zone = RadioZone.of(0)

        assertEquals(RadioZone.Plan(min = 8750, max = 10800, step = 5), zone.fm)
        assertEquals(RadioZone.Plan(min = 522, max = 1620, step = 9), zone.am)
    }

    /** Zone 3 is the OIRT band, 65.00-74.00 MHz in 30 kHz steps. */
    @Test
    fun oirtPlan() {
        assertEquals(RadioZone.Plan(min = 6500, max = 7400, step = 3), RadioZone.of(3).fm)
    }

    /** Zone 4 is Japan: 76.00-90.00 MHz FM, AM to 1629 kHz. */
    @Test
    fun japanPlan() {
        val zone = RadioZone.of(4)

        assertEquals(RadioZone.Plan(min = 7600, max = 9000, step = 10), zone.fm)
        assertEquals(RadioZone.Plan(min = 522, max = 1629, step = 9), zone.am)
    }

    /** The gateway clamps the setting to its table (EventService.java:4826-4831): out of range reads as 0. */
    @Test
    fun unknownZoneIsEurope() {
        assertEquals(RadioZone.of(0), RadioZone.of(9))
        assertEquals(RadioZone.of(0), RadioZone.of(-1))
    }

    /** `reformatFreqInt` (UIControllerBase.java:211-227): clamp to the band, then snap down to a step. */
    @Test
    fun snapClampsAndStepsDown() {
        val fm = RadioZone.of(1).fm

        assertEquals(8750, fm.snap(8000))
        assertEquals(10790, fm.snap(11000))
        assertEquals(9630, fm.snap(9639))
        assertEquals(9650, fm.snap(9650))
    }

    /** `initRadioZone(1)`: 18 FM slots then 24 AM slots, the last 12 untouched (zero). */
    @Test
    fun northAmericaDefaultsFillThirtySlots() {
        val list = RadioZone.of(1).defaultStations

        assertEquals(McuOwnerProtocol.RADIO_FREQ_LIST_SIZE, list.size)
        assertEquals(listOf(8750, 9010, 9810, 10610, 10790, 8750), list.subList(0, 6))
        assertEquals(listOf(8750, 9010, 9810, 10610, 10790, 8750), list.subList(12, 18))
        assertEquals(listOf(530, 600, 1000, 1400, 1710, 530), list.subList(18, 24))
        assertEquals(listOf(530, 600, 1000, 1400, 1710, 530), list.subList(24, 30))
        assertEquals(List(12) { 0 }, list.subList(30, 42))
    }

    /** `initRadioZone(0)`: Europe's AM defaults sit on the 9 kHz grid. */
    @Test
    fun europeDefaults() {
        val list = RadioZone.of(0).defaultStations

        assertEquals(listOf(8750, 9000, 9800, 10600, 10800, 8750), list.subList(0, 6))
        assertEquals(listOf(522, 603, 999, 1404, 1620, 522), list.subList(18, 24))
    }

    /** Slot maths the vendor does with `+ 18` for AM (MainActivity.java:170-175): FM banks 0..17, AM 18..41. */
    @Test
    fun stationSlotOffsetsAmByEighteen() {
        assertEquals(2, RadioZone.stationSlot(band = 0, position = 2))
        assertEquals(20, RadioZone.stationSlot(band = 3, position = 2))
    }
}
