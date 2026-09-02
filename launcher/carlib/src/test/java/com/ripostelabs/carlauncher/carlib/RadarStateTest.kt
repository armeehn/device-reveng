package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RadarState.fromRadarData] parses canbus2's radar frame: `[1, F1..F4, R1..R4]`, each byte a
 * distance code 30/60/90/110/150, 0xA0 clear (`HiworldCanParseToyota.java:903-921`,
 * `CanDataParseBase.java:1221-1229`). These pin the decode's contract around a broadcast we do not
 * control: never fabricate a reading, never throw, and turn "smaller = closer" into a band the UI
 * can treat as "higher = closer". The left→right order within a bank is UNVERIFIED and is not
 * asserted here beyond "index order is preserved".
 */
class RadarStateTest {

    /** Header byte + four front + four rear, the full frame. */
    private fun frame(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun nullFrameIsInvalid() {
        val state = RadarState.fromRadarData(null)

        assertFalse(state.valid)
        assertTrue(state.front.isEmpty())
        assertTrue(state.rear.isEmpty())
    }

    @Test
    fun headerOnlyFrameIsInvalid() {
        // One byte is a header with no sensor slot behind it — nothing to report.
        assertFalse(RadarState.fromRadarData(frame(0x01)).valid)
        assertFalse(RadarState.fromRadarData(ByteArray(0)).valid)
    }

    @Test
    fun distanceCodesMapToBandsNearestHighest() {
        // 30 is the closest code the parser emits, 150 the farthest.
        assertEquals(5, RadarState.band(30))
        assertEquals(4, RadarState.band(60))
        assertEquals(3, RadarState.band(90))
        assertEquals(2, RadarState.band(110))
        assertEquals(1, RadarState.band(150))
    }

    @Test
    fun clearAndNoDataAreBandZero() {
        assertEquals(0, RadarState.band(RadarState.CODE_CLEAR))
        assertEquals(0, RadarState.band(0xFF))
        assertEquals(0, RadarState.band(RadarState.CODE_NONE))
    }

    @Test
    fun fullFrameSplitsFrontAndRearInIndexOrder() {
        val state = RadarState.fromRadarData(frame(0x01, 30, 60, 90, 110, 150, 0xA0, 0xA0, 30))

        assertTrue(state.valid)
        assertEquals(listOf(5, 4, 3, 2), state.front)
        assertEquals(listOf(1, 0, 0, 5), state.rear)
    }

    @Test
    fun headerByteIsNotASensor() {
        // Regression guard for an off-by-one into byte[0]: the constant 1 header decodes as a
        // band if it is ever read as a code.
        val state = RadarState.fromRadarData(frame(0x01, 0xA0, 0xA0, 0xA0, 0xA0, 0xA0, 0xA0, 0xA0, 0xA0))

        assertEquals(listOf(0, 0, 0, 0), state.front)
        assertFalse(state.hasObstacle())
    }

    @Test
    fun truncatedFrameKeepsWhatItHas() {
        // Rear bytes never arrived. Front is still usable, so the frame is still valid.
        val state = RadarState.fromRadarData(frame(0x01, 60, 90))

        assertTrue(state.valid)
        assertEquals(listOf(4, 3), state.front)
        assertTrue(state.rear.isEmpty())
    }

    @Test
    fun smallerCodeIsCloser() {
        // The old decode had this inverted: a lower raw value must yield a higher proximity.
        val state = RadarState()
        assertTrue(state.proximity(RadarState.band(30)) > state.proximity(RadarState.band(150)))
        assertEquals(1f, state.proximity(RadarState.band(RadarState.CODE_NEAREST)), 0f)
        assertEquals(0f, state.proximity(RadarState.band(RadarState.CODE_CLEAR)), 0f)
    }

    @Test
    fun hasObstacleSpansBothBanks() {
        val clear = RadarState.fromRadarData(frame(0x01, 0xA0, 0xA0, 0xA0, 0xA0, 0xA0, 0xA0, 0xA0, 0xA0))
        val rearOnly = RadarState.fromRadarData(frame(0x01, 0xA0, 0xA0, 0xA0, 0xA0, 0xA0, 0xA0, 150, 0xA0))

        assertFalse(clear.hasObstacle())
        assertTrue(rearOnly.hasObstacle())
    }

    @Test
    fun proximityIsNormalised() {
        val state = RadarState()

        assertEquals(0f, state.proximity(0), 0f)
        assertEquals(1f, state.proximity(RadarState.LEVEL_MAX), 0f)
        // Out-of-range input must saturate, not produce a >1f alpha or a negative one.
        assertEquals(1f, state.proximity(99), 0f)
        assertEquals(0f, state.proximity(-5), 0f)
    }
}
