package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The owner path: McuOwner relays the box's own 0x11 frame under 0xA5, [HiworldCanDecoder]
 * turns it into a [CanSignal.BasicStatus], and the gesture engine reads the key byte pair off
 * that decode. Same frames and thresholds as the broadcast path, a different byte source, so
 * each case here is a payload timeline rather than a bare id.
 */
class WheelGesturesOwnerPathTest {

    private val out = mutableListOf<WheelGesture>()
    private val g = WheelGestures(out::add)

    private val FRAME = WheelGestures.FRAME_PERIOD_MS
    private val MODE_ID = 12
    private val NONE_ID = 0

    /** Relay payload for cmd 0x11: key id at p[2], held flag at p[3] (bArr[4] / bArr[5] of the frame). */
    private fun basicStatus(id: Int, held: Boolean): CanSignal.BasicStatus {
        val p = ByteArray(8)
        p[2] = id.toByte()
        p[3] = if (held) 1 else 0
        return HiworldCanDecoder.decodePayload(0x11, p) as CanSignal.BasicStatus
    }

    private fun feed(id: Int, held: Boolean, atMs: Long) = g.onSignal(basicStatus(id, held), atMs)

    /** Held frames for [id] from [from] until [LONG_PRESS_MS] has elapsed; returns the next frame time. */
    private fun holdPast(id: Int, from: Long, ms: Long): Long {
        var t = from
        while (t - from <= ms) {
            feed(id, true, t)
            t += FRAME
        }
        return t
    }

    @Test
    fun heldFramesPastTheThresholdAreALongPress() {
        val t = holdPast(MODE_ID, 0L, WheelGestures.LONG_PRESS_MS)
        assertEquals(listOf(WheelGesture.LongPress(WheelKey.MODE)), out)

        // The release frame adds nothing: the long press already stood for this press.
        feed(MODE_ID, false, t)
        assertEquals(1, out.size)
    }

    @Test
    fun twoPressesInsideTheWindowAreADoublePress() {
        feed(MODE_ID, true, 0L)
        feed(MODE_ID, false, FRAME)

        val second = FRAME + WheelGestures.DOUBLE_PRESS_MS
        feed(MODE_ID, true, second)
        feed(MODE_ID, false, second + FRAME)

        assertEquals(
            listOf(WheelGesture.Press(WheelKey.MODE), WheelGesture.DoublePress(WheelKey.MODE)),
            out,
        )
    }

    @Test
    fun twoPressesOutsideTheWindowAreTwoPlainPresses() {
        feed(MODE_ID, true, 0L)
        feed(MODE_ID, false, FRAME)

        val second = FRAME + WheelGestures.DOUBLE_PRESS_MS + 1
        feed(MODE_ID, true, second)
        feed(MODE_ID, false, second + FRAME)

        assertEquals(
            listOf(WheelGesture.Press(WheelKey.MODE), WheelGesture.Press(WheelKey.MODE)),
            out,
        )
    }

    @Test
    fun aDoorOnlyFrameIsNotAKey() {
        // The box re-sends 0x11 for door changes with the key bytes clear; a run of those,
        // however long, is no gesture.
        holdPast(NONE_ID, 0L, WheelGestures.LONG_PRESS_MS)
        feed(NONE_ID, false, WheelGestures.LONG_PRESS_MS * 2)
        assertEquals(emptyList<WheelGesture>(), out)
    }
}
