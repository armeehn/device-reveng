package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.WheelGesture
import com.ripostelabs.carlauncher.carlib.WheelKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defaults are the standing behaviour of every install that never opened the screen, and
 * the codec is what survives an upgrade that renames an action.
 */
class WheelGestureBindingsTest {

    @Test
    fun defaultsAreTheBriefsTable() {
        val b = WheelGestureBindings()
        assertTrue(b.enabled)
        assertEquals(WheelGestureAction.SEEK_FORWARD_30S, b.longOf(WheelKey.NEXT))
        assertEquals(WheelGestureAction.SEEK_BACK_10S, b.longOf(WheelKey.PREV))
        // Gateway-owned buttons ship unbound: the vendor's short action cannot be swallowed.
        assertEquals(WheelGestureAction.NONE, b.longOf(WheelKey.MODE))
        assertEquals(WheelGestureAction.MUTE_TOGGLE, b.longOf(WheelKey.PLAY_PAUSE))
        assertEquals(WheelGestureAction.NONE, b.longOf(WheelKey.TALK))
        assertEquals(WheelGestureAction.OPEN_HOME, b.longOf(WheelKey.RETURN))
        assertEquals(WheelGestureAction.NONE, b.longOf(WheelKey.MUTE))
        assertEquals(WheelGestureAction.NONE, b.longOf(WheelKey.VOICE))
    }

    @Test
    fun everyDoublePressIsNoneByDefault() {
        val b = WheelGestureBindings()
        for (key in WheelKey.values()) {
            assertEquals(key.name, WheelGestureAction.NONE, b.doubleOf(key))
        }
    }

    @Test
    fun aPlainPressNeverMapsToAnAction() {
        val b = WheelGestureBindings()
        assertEquals(WheelGestureAction.NONE, b.actionFor(WheelGesture.Press(WheelKey.NEXT)))
        assertEquals(
            WheelGestureAction.SEEK_FORWARD_30S,
            b.actionFor(WheelGesture.LongPress(WheelKey.NEXT)),
        )
    }

    @Test
    fun codecRoundTripsAndDropsNone() {
        val map = mapOf(
            WheelKey.NEXT to WheelGestureAction.NEXT_TRACK,
            WheelKey.MUTE to WheelGestureAction.NONE,
        )
        val raw = WheelGestureBindings.encode(map)
        assertEquals("NEXT=NEXT_TRACK", raw)
        assertEquals(
            mapOf(WheelKey.NEXT to WheelGestureAction.NEXT_TRACK),
            WheelGestureBindings.decode(raw, emptyMap()),
        )
    }

    @Test
    fun unstoredFallsBackButEmptyStringSticks() {
        val fallback = WheelGestureBindings.DEFAULT_LONG
        assertEquals(fallback, WheelGestureBindings.decode(null, fallback))
        assertEquals(emptyMap<WheelKey, WheelGestureAction>(), WheelGestureBindings.decode("", fallback))
    }

    @Test
    fun unknownNamesAreSkipped() {
        val decoded = WheelGestureBindings.decode("NEXT=GONE_ACTION,NOKEY=SIRI,PREV=SIRI,junk", emptyMap())
        assertEquals(mapOf(WheelKey.PREV to WheelGestureAction.SIRI), decoded)
    }
}
