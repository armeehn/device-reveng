package com.ripostelabs.carlauncher.carlib

/**
 * RadioZone — the band plan behind `SETUP_ZONE` (`05 01 z`, EventService.java:4833).
 *
 * The MCU tunes; Android only needs the plan to draw a dial and to fill the preset list
 * before the tuner has reported. Both tables are the vendor's:
 *
 *     bounds and steps   UIControllerBase.getCurrRadioFreq, UIControllerBase.java:117-209
 *                        (the landscape branch: this unit is 1920x720)
 *     preset defaults    EventService.initRadioZone, EventService.java:7275-7480
 *
 * Frequencies are the tuner's units, FM in 10 kHz (9630 = 96.30 MHz), AM in kHz. The preset
 * list is `mRadioFreqList[42]`: FM banks 1..3 in slots 0..17, AM banks in 18..41; the vendor
 * seeds only the first 30 and leaves the rest zero (empty).
 */
class RadioZone private constructor(
    val id: Int,
    val fm: Plan,
    val am: Plan,
    fmBank: List<Int>,
    amBank: List<Int>,
) {

    /** One band: bounds and dial step, all in the tuner's units. */
    data class Plan(val min: Int, val max: Int, val step: Int) {

        /** `reformatFreqInt` (UIControllerBase.java:211-227): clamp to the band, snap down to the grid. */
        fun snap(freq: Int): Int {
            if (freq < min) {
                return min
            }
            if (freq > max) {
                return max
            }
            return min + ((freq - min) / step) * step
        }
    }

    /** `initRadioZone(id)`: three FM banks then two AM banks of six, the last twelve slots empty. */
    val defaultStations: List<Int> =
        (fmBank + fmBank + fmBank + amBank + amBank + List(EMPTY_TAIL) { 0 })

    /** The plan for [band] as the vendor's `mRadioBndNum` counts them (0..2 FM, 3+ AM). */
    fun plan(band: Int): Plan = if (CarService.isAmBand(band)) am else fm

    companion object {
        private const val FM_BANKS = 3
        private const val EMPTY_TAIL = McuOwnerProtocol.RADIO_FREQ_LIST_SIZE - 5 * McuOwnerProtocol.RADIO_PRESET_COUNT

        /** The first AM slot: `i += 18` when `mRadioBndNum > 2` (MainActivity.java:170-175, FreqView.java:163-165). */
        const val AM_SLOT_OFFSET = FM_BANKS * McuOwnerProtocol.RADIO_PRESET_COUNT

        /**
         * initRadioZone(3) is the one table whose FM banks differ from each other (OIRT, then
         * two banks of CCIR), so the FM bank there is the OIRT one; the vendor's own screen
         * shows the same six for every bank once the MCU reports the real list.
         */
        private val ZONES = listOf(
            RadioZone(
                id = 0,
                fm = Plan(8750, 10800, 5), am = Plan(522, 1620, 9),
                fmBank = listOf(8750, 9000, 9800, 10600, 10800, 8750),
                amBank = listOf(522, 603, 999, 1404, 1620, 522),
            ),
            RadioZone(
                id = 1,
                fm = Plan(8750, 10790, 20), am = Plan(530, 1710, 10),
                fmBank = listOf(8750, 9010, 9810, 10610, 10790, 8750),
                amBank = listOf(530, 600, 1000, 1400, 1710, 530),
            ),
            RadioZone(
                id = 2,
                fm = Plan(8750, 10800, 10), am = Plan(520, 1620, 10),
                fmBank = listOf(8750, 9010, 9810, 10610, 10800, 8750),
                amBank = listOf(530, 600, 1000, 1400, 1710, 530),
            ),
            RadioZone(
                id = 3,
                fm = Plan(6500, 7400, 3), am = Plan(522, 1620, 9),
                fmBank = listOf(6500, 6710, 7040, 7250, 7400, 6500),
                amBank = listOf(522, 603, 999, 1404, 1620, 522),
            ),
            RadioZone(
                id = 4,
                fm = Plan(7600, 9000, 10), am = Plan(522, 1629, 9),
                fmBank = listOf(7600, 7640, 8560, 8700, 9000, 7600),
                amBank = listOf(522, 603, 999, 1404, 1629, 522),
            ),
            RadioZone(
                id = 5,
                fm = Plan(8750, 10800, 10), am = Plan(530, 1710, 9),
                fmBank = listOf(8750, 9000, 9800, 10600, 10800, 8750),
                amBank = listOf(522, 603, 999, 1404, 1710, 530),
            ),
        )

        /** The zone for a `KEY_RADIO_ZONE_SETTINGS` value; anything off the table reads as 0, the vendor default. */
        fun of(id: Int): RadioZone = ZONES.getOrElse(id) { ZONES[0] }

        /** Slot in the 42-entry list for [position] within [band]'s bank list, the vendor's `+ 18` for AM. */
        fun stationSlot(band: Int, position: Int): Int =
            if (CarService.isAmBand(band)) AM_SLOT_OFFSET + position else position
    }
}
