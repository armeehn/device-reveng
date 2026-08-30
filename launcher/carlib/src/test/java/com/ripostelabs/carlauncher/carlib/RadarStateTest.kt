package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RadarState.fromRadarData] parses a broadcast extra we do not control, so the frames it will
 * meet on-device include null, truncated, and out-of-range ones. The byte layout itself is
 * GUESSED (see the class KDoc) and will be corrected after a live capture — these tests pin the
 * *contract* around it: never fabricate a reading, never throw, never leak a raw byte into a
 * level. A layout fix should change the offsets here and nothing else.
 */
class RadarStateTest {

    /** Header byte + four front + four rear, the full guessed frame. */
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
        assertFalse(RadarState.fromRadarData(frame(0x00)).valid)
        assertFalse(RadarState.fromRadarData(ByteArray(0)).valid)
    }

    @Test
    fun fullFrameSplitsFrontAndRear() {
        val state = RadarState.fromRadarData(frame(0x00, 1, 2, 3, 4, 5, 6, 7, 8))

        assertTrue(state.valid)
        assertEquals(listOf(1, 2, 3, 4), state.front)
        assertEquals(listOf(5, 6, 7, 8), state.rear)
    }

    @Test
    fun headerByteIsNotASensor() {
        // Regression guard for an off-by-one into byte[0]: a loud header must not appear as a
        // front-left obstacle.
        val state = RadarState.fromRadarData(frame(0x07, 0, 0, 0, 0, 0, 0, 0, 0))

        assertEquals(listOf(0, 0, 0, 0), state.front)
        assertFalse(state.hasObstacle())
    }

    @Test
    fun truncatedFrameKeepsWhatItHas() {
        // Rear bytes never arrived. Front is still usable, so the frame is still valid.
        val state = RadarState.fromRadarData(frame(0x00, 2, 3))

        assertTrue(state.valid)
        assertEquals(listOf(2, 3), state.front)
        assertTrue(state.rear.isEmpty())
    }

    @Test
    fun sensorOffReadsAsClear() {
        // 0xFF is the usual "sensor disabled / no data" marker. Left raw it would clamp to
        // LEVEL_MAX and paint every bar as an imminent collision.
        val state = RadarState.fromRadarData(frame(0x00, 0xFF, 0xFF, 0xFF, 0xFF))

        assertEquals(listOf(0, 0, 0, 0), state.front)
        assertFalse(state.hasObstacle())
    }

    @Test
    fun outOfRangeLevelClamps() {
        val state = RadarState.fromRadarData(frame(0x00, 9, 200, 0xFE, 0))

        assertEquals(
            listOf(RadarState.LEVEL_MAX, RadarState.LEVEL_MAX, RadarState.LEVEL_MAX, 0),
            state.front,
        )
    }

    @Test
    fun hasObstacleSpansBothBanks() {
        val clear = RadarState.fromRadarData(frame(0x00, 0, 0, 0, 0, 0, 0, 0, 0))
        val rearOnly = RadarState.fromRadarData(frame(0x00, 0, 0, 0, 0, 0, 0, 1, 0))

        assertFalse(clear.hasObstacle())
        assertTrue(rearOnly.hasObstacle())
    }

    @Test
    fun proximityIsNormalised() {
        val state = RadarState()

        assertEquals(0f, state.proximity(0), 0f)
        assertEquals(1f, state.proximity(RadarState.LEVEL_MAX), 0f)
        assertEquals(0.5f, state.proximity(RadarState.LEVEL_MAX / 2), 0f)
        // Out-of-range input must saturate, not produce a >1f alpha or a negative one.
        assertEquals(1f, state.proximity(99), 0f)
        assertEquals(0f, state.proximity(-5), 0f)
    }
}
