package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the owner path tells the MCU about Android sound and CarPlay, against the vendor's order. */
class ArmAudioRouteTest {

    private val frames = mutableListOf<ByteArray>()
    private val modes = mutableListOf<McuOwnerProtocol.Mode>()

    private val mcu = object : ArmAudioRoute.Mcu {
        override var lastMode: McuOwnerProtocol.Mode? = null

        override fun setMode(mode: McuOwnerProtocol.Mode): Boolean {
            lastMode = mode
            modes += mode
            return true
        }

        override fun send(frame: ByteArray) {
            frames += frame
        }
    }

    private val route = ArmAudioRoute(mcu)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** `3F nav system` (EventService.java:8079): nav stays 0, system follows Android playback. */
    @Test
    fun soundStartAndStopSendTheSystemByte() {
        route.onAndroidSound(playing = true)
        route.onAndroidSound(playing = false)

        assertEquals(2, frames.size)
        assertArrayEquals(McuSerial.encode(0x3F, bytes(0x00, 0x01)), frames[0])
        assertArrayEquals(McuSerial.encode(0x3F, bytes(0x00, 0x00)), frames[1])
    }

    /** The vendor's mSystemPlay starts false and sends only on change (EventService.java:461). */
    @Test
    fun repeatedOrInitialSilenceSendsNothing() {
        route.onAndroidSound(playing = false)
        route.onAndroidSound(playing = true)
        route.onAndroidSound(playing = true)

        assertEquals(1, frames.size)
    }

    /** CONNECTED selects SRC_CARPLAY (ZlinkManage.java:246, :363-366); DISCONNECT exits to NULL. */
    @Test
    fun projectionConnectSelectsCarPlayAndDisconnectExits() {
        route.onProjection(connected = true)
        route.onProjection(connected = true)
        route.onProjection(connected = false)

        assertEquals(listOf(McuOwnerProtocol.Mode.CARPLAY, McuOwnerProtocol.Mode.NULL), modes)
    }

    /** exitCurMode is a no-op when another source took over (ZlinkManage.java:261, EventService.java:8925). */
    @Test
    fun disconnectLeavesAnotherSourceAlone() {
        route.onProjection(connected = true)
        mcu.setMode(McuOwnerProtocol.Mode.RADIO)
        modes.clear()

        route.onProjection(connected = false)

        assertTrue(modes.isEmpty())
    }
}
