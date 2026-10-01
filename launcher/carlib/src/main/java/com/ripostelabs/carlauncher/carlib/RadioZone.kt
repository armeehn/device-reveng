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

    /**
     * The FM station to tune after switching to this zone: the bottom of FM when coming from
     * AM, else [freq] clamped and snapped onto this zone's FM grid.
     */
    fun entryFreq(onAm: Boolean, freq: Int): Int = if (onAm) fm.min else fm.snap(freq)

    /**
     * The regions the vendor radio's picker steps through, in its `radio_zone` order. The MCU
     * takes 0..4 and nothing else (the gateway clamps), so these five are every plan it has.
     * Labels name where each plan is right, from the band plans in radio-bands.md:
     *
     *     0  9 kHz AM, FM 87.5-108     ITU Regions 1 and 3 (GE75, GE84), Australia
     *     1  10 kHz AM, FM odd tenths  US and Canada (47 CFR 73.14, 73.201)
     *     2  10 kHz AM, FM 100 kHz     the rest of ITU Region 2
     *     3  OIRT FM 65-74             the legacy Soviet band; modern Russian FM is zone 0
     *     4  FM 76-90                  Japan
     */
    enum class Region(val id: Int, val label: String) {
        EUROPE(0, "Europe, Africa, Asia, Australia"),
        NORTH_AMERICA(1, "North America"),
        SOUTH_AMERICA(2, "Latin America"),
        RUSSIA(3, "Russia, OIRT 65-74 MHz"),
        JAPAN(4, "Japan"),
        ;

        companion object {
            /** The gateway clamps above 4 to 4 (EventService.java:4825-4830); below 0 reads as Europe. */
            fun of(id: Int): Region = entries[id.coerceIn(0, entries.size - 1)]
        }
    }

    /** The plan for [band] as the vendor's `mRadioBndNum` counts them (0..2 FM, 3+ AM). */
    fun plan(band: Int): Plan = if (CarService.isAmBand(band)) am else fm

    companion object {
        private const val FM_BANKS = 3
        private const val FIRST_AM_BAND = 3
        private const val EMPTY_TAIL = McuOwnerProtocol.RADIO_FREQ_LIST_SIZE - 5 * McuOwnerProtocol.RADIO_PRESET_COUNT

        /** `KEY_RADIO_ZONE_SETTINGS` 1: FM 87.5-107.9 on 200 kHz, AM 530-1710 on 10 kHz. */
        const val NORTH_AMERICA = 1

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

        /**
         * Slot in the 42-entry list for [position] within [band]'s bank: bank x 6 + position, as
         * stock's full grid counts it (FreqView.java:163-186). FM1 0-5, FM2 6-11, FM3 12-17,
         * AM1 18-23, AM2 24-29.
         */
        fun stationSlot(band: Int, position: Int): Int {
            if (CarService.isAmBand(band)) {
                return AM_SLOT_OFFSET + (band - FIRST_AM_BAND) * McuOwnerProtocol.RADIO_PRESET_COUNT + position
            }
            return maxOf(0, band) * McuOwnerProtocol.RADIO_PRESET_COUNT + position
        }
    }
}
