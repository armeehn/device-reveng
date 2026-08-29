package com.reveng.carlauncher.input

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cancel path is what stands between a lost ACTION_UP and a key that auto-repeats forever:
 * MainActivity calls [KeyPump.cancel] on window-focus loss and for FLAG_CANCELED releases, and
 * both of those only work if cancel really stops the repeat timer and voids the held key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyPumpTest {

    @Test
    fun cancelStopsAutoRepeat() = runTest {
        val events = mutableListOf<NavEvent>()
        val pump = KeyPump(backgroundScope, events::add)

        pump.down(NavKey.DOWN)
        runCurrent()
        assertEquals(listOf<NavEvent>(NavEvent.Press(NavKey.DOWN)), events)

        // Past the initial delay and a few repeat intervals: the key is repeating.
        advanceTimeBy(2_000)
        runCurrent()
        val whileHeld = events.size
        assertTrue("expected auto-repeat ticks", whileHeld > 1)
        assertTrue(events.last() == NavEvent.Press(NavKey.DOWN, repeat = true))

        // The release is lost; cancel (focus loss) must stop the repeats for good.
        pump.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(whileHeld, events.size)
    }

    @Test
    fun upAfterCancelDoesNotFireTheDeferredPress() = runTest {
        val events = mutableListOf<NavEvent>()
        val pump = KeyPump(backgroundScope, events::add)

        // CENTER defers its short action to the release; a cancel in between (FLAG_CANCELED)
        // means the system took the press back — the release must not activate anything.
        pump.down(NavKey.CENTER)
        runCurrent()
        pump.cancel()
        pump.up(NavKey.CENTER)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList<NavEvent>(), events)
    }

    @Test
    fun cancelBeforeLongPressStopsTheTimer() = runTest {
        val events = mutableListOf<NavEvent>()
        val pump = KeyPump(backgroundScope, events::add)

        pump.down(NavKey.BACK)
        runCurrent()
        pump.cancel()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList<NavEvent>(), events)
    }
}
