package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mode sequence the owner path sends for the tuner, against the vendor's own order. */
class RadioSourceTest {

    private val sent = mutableListOf<McuOwnerProtocol.Mode>()

    private fun source(ack: Boolean = true) = RadioSource(select = { mode ->
        sent += mode
        ack
    })

    @Test
    fun claimSelectsRadioAndReleaseSendsNull() {
        val source = source()

        assertTrue(source.claim())
        assertTrue(source.held)
        assertTrue(source.release())
        assertFalse(source.held)

        assertEquals(listOf(McuOwnerProtocol.Mode.RADIO, McuOwnerProtocol.Mode.NULL), sent)
    }

    /** exitCurMode is a no-op unless the mode is ours: no frame when nothing was claimed. */
    @Test
    fun releaseWithoutClaimSendsNothing() {
        val source = source()

        assertFalse(source.release())
        assertTrue(sent.isEmpty())
    }

    /** setCurModeCallback records the mode before sendDataWaitAck: a missed ACK still holds. */
    @Test
    fun lateAckStillHolds() {
        val source = source(ack = false)

        assertFalse(source.claim())
        assertTrue(source.held)
        assertTrue(source.release())
    }

    /** A second visit to the tuner re-sends SRC_RADIO, as the vendor radio does on each resume. */
    @Test
    fun reclaimSendsAgain() {
        val source = source()

        source.claim()
        source.claim()
        source.release()
        source.release()

        assertEquals(
            listOf(McuOwnerProtocol.Mode.RADIO, McuOwnerProtocol.Mode.RADIO, McuOwnerProtocol.Mode.NULL),
            sent,
        )
    }

    /**
     * The vendor radio clears the MCU's voice-duck flag around both edges: `42 00` after it
     * sends the mode (MainActivity.java:503-516) and again before `exitCurMode` (:742-758).
     */
    @Test
    fun voiceFlagClearedOnBothEdges() {
        val log = mutableListOf<String>()
        val source = RadioSource(
            select = { mode -> log += "mode:${mode.name}"; true },
            voice = { on -> log += "voice:$on" },
        )

        source.claim()
        source.release()

        assertEquals(listOf("mode:RADIO", "voice:false", "voice:false", "mode:NULL"), log)
    }
}

/** exitCurMode: SRC_NULL only while the valid mode is still the tuner (EventService.java:8925). */
class RadioSourceTakeoverTest {

    @Test
    fun releaseAfterAnotherSourceTookOverSendsNothing() {
        val sent = mutableListOf<McuOwnerProtocol.Mode>()
        var current: McuOwnerProtocol.Mode? = null
        val source = RadioSource(select = { mode -> sent += mode; current = mode; true }, current = { current })

        source.claim()
        current = McuOwnerProtocol.Mode.MUSIC
        assertFalse(source.release())
        assertFalse(source.held)

        assertEquals(listOf(McuOwnerProtocol.Mode.RADIO), sent)
    }
}
