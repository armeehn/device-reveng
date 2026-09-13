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

    /** The station list is not kept, so it must not wake the screen either. */
    @Test
    fun freqListDoesNotTick() {
        now = 5
        holder.onRadio(McuOwnerProtocol.RadioEvent.FreqList(index = 2, freq = 9630))

        assertEquals(0, ticks)
        assertEquals(0L, holder.state.value.updatedAt)
    }
}
