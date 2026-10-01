package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fold the vendor's `mRadio*` fields do (EventService.java:2763-2845), one event at a time. */
class RadioStateHolderTest {

    private var now = 0L
    private var ticks = 0
    private val holder = RadioStateHolder(clock = { now }, onUpdate = { ticks++ })

    @Test
    fun startsUnknown() {
        assertEquals(RadioState(), holder.state.value)
        assertEquals(0L, holder.state.value.updatedAt)
    }

    /**
     * An AM frequency under an FM band is a missed band report, not 5.30 MHz: the car showed
     * "5.30 MHz" and "11.80 MHz" on 2026-09-30 while the MCU was on AM. The frequency's own
     * range wins, AM1 for kHz, FM1 for an FM value.
     */
    @Test
    fun amFrequencyUnderFmBandIsAm() {
        holder.onRadio(McuOwnerProtocol.RadioEvent.Band(band = 1, preset = null))
        holder.onRadio(McuOwnerProtocol.RadioEvent.Frequency(530))
        assertEquals(3, holder.state.value.band)
    }

    @Test
    fun fmFrequencyUnderAmBandIsFm() {
        holder.onRadio(McuOwnerProtocol.RadioEvent.Band(band = 4, preset = null))
        holder.onRadio(McuOwnerProtocol.RadioEvent.Frequency(9630))
        assertEquals(0, holder.state.value.band)
    }

    /** A band that agrees with the frequency is kept as reported: AM2 stays AM2. */
    @Test
    fun agreeingBandIsKept() {
        holder.onRadio(McuOwnerProtocol.RadioEvent.Band(band = 4, preset = null))
        holder.onRadio(McuOwnerProtocol.RadioEvent.Frequency(1150))
        assertEquals(4, holder.state.value.band)
        holder.onRadio(McuOwnerProtocol.RadioEvent.Band(band = 2, preset = null))
        holder.onRadio(McuOwnerProtocol.RadioEvent.Frequency(6590))
        assertEquals(2, holder.state.value.band)
    }

    @Test
    fun eachEventOverwritesItsFieldOnly() {
        now = 10
        holder.onRadio(McuOwnerProtocol.RadioEvent.Band(band = 3, preset = 2))
        now = 20
        holder.onRadio(McuOwnerProtocol.RadioEvent.Frequency(1010))
        now = 30
        holder.onRadio(McuOwnerProtocol.RadioEvent.StationName("CBC R1"))
        now = 40
        holder.onRadio(
            McuOwnerProtocol.RadioEvent.State(
                stereoIcon = true, tpIcon = false, traffic = false, noPty = false,
                rds = true, pty = false, af = false, ta = true, stMono = false, loc = false, ams = false, aps = false,
            ),
        )

        val s = holder.state.value
        assertEquals(3, s.band)
        assertEquals(2, s.presetNumber)
        assertEquals(1010, s.freq)
        assertEquals("CBC R1", s.stationName)
        assertTrue(s.rds)
        assertTrue(s.ta)
        assertTrue(s.stereo)
        assertFalse(s.af)
        assertFalse(s.tp)
        assertEquals(40L, s.updatedAt)
        assertEquals(4, ticks)
    }

    /** A band frame with only the slot in range leaves the band alone, as onRadioBndNum does. */
    @Test
    fun partialBandKeepsTheOtherHalf() {
        holder.onRadio(McuOwnerProtocol.RadioEvent.Band(band = 1, preset = 0))
        holder.onRadio(McuOwnerProtocol.RadioEvent.Band(band = null, preset = 5))

        assertEquals(1, holder.state.value.band)
        assertEquals(5, holder.state.value.presetNumber)
    }

    /**
     * During auto-store (AMS) sub 4/8 fill one of the 42 slots (RAV4-97 preset list); an
     * out-of-range slot is dropped.
     */
    @Test
    fun freqListFillsItsSlot() {
        now = 5
        holder.onRadio(autoStore(running = true))
        holder.onRadio(McuOwnerProtocol.RadioEvent.FreqList(index = 2, freq = 9630))
        holder.onRadio(McuOwnerProtocol.RadioEvent.FreqList(index = McuOwnerProtocol.RADIO_FREQ_LIST_SIZE, freq = 1))

        assertEquals(2, ticks)
        assertEquals(McuOwnerProtocol.RADIO_FREQ_LIST_SIZE, holder.state.value.stationList.size)
        assertEquals(9630, holder.state.value.stationList[2])
        assertEquals(5L, holder.state.value.updatedAt)
    }

    /** Sub 0 carries the ST/MONO and DX/LOC settings as well as the icons. */
    @Test
    fun stateCarriesTheTunerSettings() {
        holder.onRadio(
            McuOwnerProtocol.RadioEvent.State(
                stereoIcon = true, tpIcon = false, traffic = false, noPty = false, rds = true, pty = false,
                af = false, ta = false, stMono = true, loc = true, ams = false, aps = false,
            ),
        )

        assertTrue(holder.state.value.stMono)
        assertTrue(holder.state.value.dxLoc)
    }

    /** Bits 6 and 7 of the flag byte are the AMS and APS runs the vendor shows as "searching"/"scanning". */
    @Test
    fun stateCarriesScanRuns() {
        holder.onRadio(
            McuOwnerProtocol.RadioEvent.State(
                stereoIcon = false, tpIcon = false, traffic = true, noPty = false,
                rds = false, pty = true, af = false, ta = false, stMono = false, loc = false, ams = true, aps = false,
            ),
        )

        val s = holder.state.value
        assertTrue(s.autoStoring)
        assertFalse(s.scanning)
        assertTrue(s.ptyEnabled)
        assertTrue(s.traffic)
    }

    /** A seed fills the cache before the MCU speaks, as `initRadioZone` fills `mRadioFreqList`. */
    @Test
    fun seedAppliesOnlyWhileNothingHeard() {
        val seed = RadioState(band = 1, freq = 9630, zone = 1, stationList = RadioZone.of(1).defaultStations)

        holder.seed(seed)
        assertEquals(seed, holder.state.value)
        assertEquals(0, ticks)

        now = 5
        holder.onRadio(McuOwnerProtocol.RadioEvent.Frequency(1010))
        holder.seed(RadioState(freq = 8750))

        assertEquals(1010, holder.state.value.freq)
        assertEquals(1, holder.state.value.zone)
    }

    /**
     * A long press stores the station on air into its slot at once. The car (2026-10-01) never
     * logged a list report after `02 65`, so waiting for the MCU's echo left the slot on the
     * zone default and the saved station was gone after a reboot.
     */
    @Test
    fun storeKeepsTheStationOnAir() {
        holder.seed(RadioState(zone = 1, stationList = RadioZone.of(1).defaultStations))
        now = 5
        holder.onRadio(McuOwnerProtocol.RadioEvent.Frequency(8970))

        holder.store(slot = 6)

        assertEquals(8970, holder.state.value.stationList[6])
        assertEquals(2, ticks)
    }

    /** Nothing on air yet (freq 0) is no station to keep; a slot past the 42 is dropped. */
    @Test
    fun storeWithoutStationIsIgnored() {
        holder.store(slot = 0)
        holder.onRadio(McuOwnerProtocol.RadioEvent.Frequency(8970))
        holder.store(slot = McuOwnerProtocol.RADIO_FREQ_LIST_SIZE)

        assertEquals(0, holder.state.value.stationList[0])
        assertEquals(1, ticks)
    }

    /**
     * The saved list is the truth across boots. A list report outside auto-store, such as the
     * MCU's table after the boot-time region frame (`05 01 z`), must not put the zone defaults
     * back over a stored preset.
     */
    @Test
    fun listReportOutsideAutoStoreKeepsSavedPreset() {
        val saved = RadioZone.of(1).defaultStations.toMutableList().also { it[0] = 8970; it[18] = 1150 }
        holder.seed(RadioState(zone = 1, stationList = saved))

        now = 5
        holder.onRadio(McuOwnerProtocol.RadioEvent.FreqList(index = 0, freq = 8750))
        holder.onRadio(McuOwnerProtocol.RadioEvent.FreqList(index = 18, freq = 530))

        assertEquals(saved, holder.state.value.stationList)
    }

    /** The MCU's slot reports trail the AMS flag: a short tail after it clears still counts. */
    @Test
    fun autoStoreTailIsAcceptedThenClosed() {
        now = 1_000
        holder.onRadio(autoStore(running = true))
        now = 2_000
        holder.onRadio(autoStore(running = false))

        now = 3_000
        holder.onRadio(McuOwnerProtocol.RadioEvent.FreqList(index = 1, freq = 9050))
        now = 60_000
        holder.onRadio(McuOwnerProtocol.RadioEvent.FreqList(index = 1, freq = 8750))

        assertEquals(9050, holder.state.value.stationList[1])
    }

    private fun autoStore(running: Boolean) = McuOwnerProtocol.RadioEvent.State(
        stereoIcon = false, tpIcon = false, traffic = false, noPty = false, rds = false, pty = false,
        af = false, ta = false, stMono = false, loc = false, ams = running, aps = false,
    )
}
