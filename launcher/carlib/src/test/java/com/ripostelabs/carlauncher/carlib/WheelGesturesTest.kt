package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gesture engine is what stands between a 10 Hz frame stream and a driver's intent. Each
 * case here is a frame timeline: the vendor path never sees duration, so nothing else can be
 * asked to catch a wrong reading.
 */
class WheelGesturesTest {

    private val out = mutableListOf<WheelGesture>()
    private val g = WheelGestures(out::add)

    private val FRAME = WheelGestures.FRAME_PERIOD_MS
    private val NEXT_ID = 9
    private val PREV_ID = 8
    private val PREV_ALIAS_ID = 13
    private val VOL_UP_ID = 1
    private val NONE_ID = 0

    /** [count] held frames for [id] from [from], one per [FRAME]; returns the next frame time. */
    private fun hold(id: Int, from: Long, count: Int): Long {
        var t = from
        repeat(count) {
            g.onSample(id, true, t)
            t += FRAME
        }
        return t
    }

    @Test
    fun shortPressEmitsPressOnRelease() {
        val t = hold(NEXT_ID, 0, 2)
        assertEquals(emptyList<WheelGesture>(), out)

        g.onSample(NONE_ID, false, t)
        assertEquals(listOf<WheelGesture>(WheelGesture.Press(WheelKey.NEXT)), out)
    }

    @Test
    fun holdEmitsLongPressWhileStillHeldAndNothingOnRelease() {
        val t = hold(NEXT_ID, 0, 7) // frames at 0..600 ms
        assertEquals(listOf<WheelGesture>(WheelGesture.LongPress(WheelKey.NEXT)), out)

        hold(NEXT_ID, t, 5)
        g.onSample(NONE_ID, false, t + 5 * FRAME)
        assertEquals(1, out.size)
    }

    @Test
    fun longPressFiresFromTheTickWhenFramesAreSlow() {
        hold(NEXT_ID, 0, 1)
        // The gap deadline comes first; the long-press one only once frames keep arriving.
        assertEquals(WheelGestures.FRAME_GAP_MS + 1, g.nextDeadlineMs())

        // Frames keep the key alive but are sparse; the tick at the deadline still fires it.
        g.onSample(NEXT_ID, true, 250)
        g.onSample(NEXT_ID, true, 500)
        g.onTick(WheelGestures.LONG_PRESS_MS)
        assertEquals(listOf<WheelGesture>(WheelGesture.LongPress(WheelKey.NEXT)), out)
    }

    @Test
    fun doublePressIsTheSecondDownWithinTheWindow() {
        hold(NEXT_ID, 0, 2)
        g.onSample(NONE_ID, false, 200)
        hold(NEXT_ID, 200 + WheelGestures.DOUBLE_PRESS_MS, 1)
        assertEquals(
            listOf(WheelGesture.Press(WheelKey.NEXT), WheelGesture.DoublePress(WheelKey.NEXT)),
            out,
        )

        // The second press of a pair reports nothing else, even if held long.
        hold(NEXT_ID, 700, 10)
        g.onSample(NONE_ID, false, 1_700)
        assertEquals(2, out.size)
    }

    @Test
    fun aLatePressIsJustAnotherPress() {
        hold(NEXT_ID, 0, 1)
        g.onSample(NONE_ID, false, 100)
        hold(NEXT_ID, 100 + WheelGestures.DOUBLE_PRESS_MS + 1, 1)
        g.onSample(NONE_ID, false, 700)
        assertEquals(
            listOf(WheelGesture.Press(WheelKey.NEXT), WheelGesture.Press(WheelKey.NEXT)),
            out,
        )
    }

    @Test
    fun aDifferentKeyDoesNotPairIntoADouble() {
        hold(NEXT_ID, 0, 1)
        g.onSample(NONE_ID, false, 100)
        hold(PREV_ID, 200, 1)
        g.onSample(NONE_ID, false, 300)
        assertEquals(
            listOf(WheelGesture.Press(WheelKey.NEXT), WheelGesture.Press(WheelKey.PREV)),
            out,
        )
    }

    @Test
    fun aLongPressDoesNotSeedADouble() {
        val t = hold(NEXT_ID, 0, 7)
        g.onSample(NONE_ID, false, t)
        hold(NEXT_ID, t + 100, 1)
        g.onSample(NONE_ID, false, t + 200)
        assertEquals(
            listOf(WheelGesture.LongPress(WheelKey.NEXT), WheelGesture.Press(WheelKey.NEXT)),
            out,
        )
    }

    @Test
    fun aFrameGapWithoutAReleaseFrameIsARelease() {
        hold(NEXT_ID, 0, 2)
        val deadline = g.nextDeadlineMs()!!
        assertEquals(FRAME + WheelGestures.FRAME_GAP_MS + 1, deadline)

        g.onTick(deadline)
        assertEquals(listOf<WheelGesture>(WheelGesture.Press(WheelKey.NEXT)), out)
        assertNull(g.nextDeadlineMs())
    }

    @Test
    fun aTickInsideTheGapIsNotARelease() {
        hold(NEXT_ID, 0, 1)
        g.onTick(WheelGestures.FRAME_GAP_MS)
        assertEquals(emptyList<WheelGesture>(), out)
    }

    @Test
    fun volumeKeysAreIgnored() {
        hold(VOL_UP_ID, 0, 10)
        g.onSample(NONE_ID, false, 1_000)
        assertEquals(emptyList<WheelGesture>(), out)
        assertNull(g.nextDeadlineMs())
    }

    @Test
    fun aliasIdsFoldOntoOneKey() {
        hold(PREV_ID, 0, 1)
        g.onSample(NONE_ID, false, 100)
        hold(PREV_ALIAS_ID, 200, 1)
        assertEquals(
            listOf(WheelGesture.Press(WheelKey.PREV), WheelGesture.DoublePress(WheelKey.PREV)),
            out,
        )
        assertEquals(WheelKey.NEXT, WheelKey.fromCanId(14))
        assertNull(WheelKey.fromCanId(VOL_UP_ID))
        assertNull(WheelKey.fromCanId(7))
    }

    @Test
    fun aKeyChangeWithoutAReleaseFrameEndsTheFirstPress() {
        hold(NEXT_ID, 0, 2)
        hold(PREV_ID, 200, 1)
        assertEquals(listOf<WheelGesture>(WheelGesture.Press(WheelKey.NEXT)), out)

        g.onSample(NONE_ID, false, 300)
        assertEquals(WheelGesture.Press(WheelKey.PREV), out.last())
    }

    @Test
    fun mcuCodesMapBackToKeys() {
        assertEquals(WheelKey.NEXT, WheelKey.fromMcuKey(SwcFallback.MCU_KEY_NEXT))
        assertEquals(WheelKey.RETURN, WheelKey.fromMcuKey(SwcFallback.MCU_KEY_RETURN))
        assertEquals(WheelKey.VOICE, WheelKey.fromMcuKey(SwcFallback.MCU_KEY_VOICE))
        assertNull(WheelKey.fromMcuKey(SwcFallback.MCU_KEY_VOL_ADD))
        assertNull(WheelKey.fromMcuKey(null))
    }
}
