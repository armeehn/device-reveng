package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.CanableUsbLink.Companion.startupFor
import com.ripostelabs.carlauncher.carlib.CanableUsbLink.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A listening session must write nothing at all.
 *
 * This is the one property of the MCU wire tap that matters more than whether it works. The same
 * commands that open a CAN adapter would, on that link, be bytes injected into a live conversation
 * between the head unit and the car's decoder box. An adapter clipped onto the box's transmit line
 * has no business speaking, and a tap that talks is worse than no tap.
 *
 * Pinned as a pure function rather than trusted to a code path nobody can run without hardware.
 */
class SessionModeTest {

    @Test
    fun `a listening session sends nothing at startup`() {
        val startup = startupFor(SessionMode.LISTEN, SlcanBitrate.KBIT_500)

        assertEquals(emptyList<ByteArray>(), startup)
    }

    /** True for every bitrate: none of them is an excuse to put a byte on the wire. */
    @Test
    fun `no bitrate makes a listening session talk`() {
        SlcanBitrate.entries.forEach {
            assertTrue(it.name, startupFor(SessionMode.LISTEN, it).isEmpty())
        }
    }

    /** The CAN adapter still needs its channel opened, or it reports a dead bus forever. */
    @Test
    fun `a CAN session still opens its channel`() {
        val startup = startupFor(SessionMode.SLCAN, SlcanBitrate.KBIT_500)

        assertTrue("slcan startup was empty", startup.isNotEmpty())
        assertEquals(SlcanCodec.startup(SlcanBitrate.KBIT_500).size, startup.size)
    }

    /** The two modes must not quietly become the same thing. */
    @Test
    fun `the modes differ`() {
        val listening = startupFor(SessionMode.LISTEN, SlcanBitrate.KBIT_500)
        val can = startupFor(SessionMode.SLCAN, SlcanBitrate.KBIT_500)

        assertTrue(listening.size < can.size)
    }
}
