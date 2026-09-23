package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.ArmAudioRoute.Playback
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol.Mode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** What the owner path tells the MCU about Android sound and the media sources, against the vendor's order. */
class ArmAudioRouteTest {

    private val frames = mutableListOf<ByteArray>()
    private val modes = mutableListOf<Mode>()

    private val mcu = object : ArmAudioRoute.Mcu {
        override var lastMode: Mode? = null

        override fun setMode(mode: Mode): Boolean {
            lastMode = mode
            modes += mode
            return true
        }

        override fun send(frame: ByteArray) {
            frames += frame
        }
    }

    /** Inputs run inline: the test thread is the route's thread. */
    private val route = ArmAudioRoute(mcu, Executor { it.run() })

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun framesOf(opcode: Int) = frames.filter { it[OPCODE_INDEX].toInt() == opcode }

    // ---- `3F nav system` ------------------------------------------------------------------

    /** `3F nav system` (EventService.java:8079): nav stays 0, system follows Android playback. */
    @Test
    fun soundStartAndStopSendTheSystemByte() {
        route.onPlayback(Playback(any = true))
        route.onPlayback(Playback())

        val sound = framesOf(OP_SOUND_STATE)
        assertEquals(2, sound.size)
        assertArrayEquals(McuSerial.encode(OP_SOUND_STATE, bytes(0x00, 0x01)), sound[0])
        assertArrayEquals(McuSerial.encode(OP_SOUND_STATE, bytes(0x00, 0x00)), sound[1])
    }

    /** The vendor's mSystemPlay starts false and sends only on change (EventService.java:461). */
    @Test
    fun repeatedOrInitialSilenceSendsNothing() {
        route.onPlayback(Playback())
        route.onPlayback(Playback(any = true))
        route.onPlayback(Playback(any = true))

        assertEquals(1, framesOf(OP_SOUND_STATE).size)
    }

    /** A guidance player is the nav byte (sendNavStateToMcu :8076-8079); music under it keeps system=1. */
    @Test
    fun navigationPromptSetsTheNavByte() {
        route.onPlayback(Playback(media = true, any = true))
        route.onPlayback(Playback(media = true, nav = true, any = true))
        route.onPlayback(Playback(media = true, any = true))

        val sound = framesOf(OP_SOUND_STATE)
        assertEquals(3, sound.size)
        assertArrayEquals(McuSerial.encode(OP_SOUND_STATE, bytes(0x01, 0x01)), sound[1])
        assertArrayEquals(McuSerial.encode(OP_SOUND_STATE, bytes(0x00, 0x01)), sound[2])
    }

    // ---- media players --------------------------------------------------------------------

    /** Play state 3 sends SRC_MUSIC (MusicPlayerService.java:121-125, :1097-1102); pause keeps it (:474-478). */
    @Test
    fun mediaStartSelectsMusicAndPauseHolds() {
        route.onPlayback(Playback(media = true, any = true))
        route.onPlayback(Playback())

        assertEquals(listOf(Mode.MUSIC), modes)
    }

    /** The videoplayer is the same code with SRC_MOVIE (VideoPlayerService.java:1010). */
    @Test
    fun movieContentSelectsMovie() {
        route.onPlayback(Playback(media = true, movie = true, any = true))

        assertEquals(listOf(Mode.MOVIE), modes)
    }

    /** play()/pause() wrap the transition in `4C 14` and report it as `19` (MusicPlayerService.java:1120, :531 → EventService.java:4328). */
    @Test
    fun mediaEdgesSendTimedMuteAndPlayState() {
        route.onPlayback(Playback(media = true, any = true))
        route.onPlayback(Playback())

        assertEquals(2, framesOf(OP_TIMED_MUTE).size)
        assertArrayEquals(McuSerial.encode(OP_TIMED_MUTE, bytes(TIMED_MUTE_UNITS)), framesOf(OP_TIMED_MUTE)[0])
        assertArrayEquals(McuSerial.encode(OP_PLAY_STATE, bytes(0x00)), framesOf(OP_PLAY_STATE)[0])
        assertArrayEquals(McuSerial.encode(OP_PLAY_STATE, bytes(0x01)), framesOf(OP_PLAY_STATE)[1])
    }

    /** Each play start re-sends the mode, as sendMode has no same-mode check (EventService.java:3933). */
    @Test
    fun everyMediaStartResendsTheMode() {
        route.onPlayback(Playback(media = true, any = true))
        route.onPlayback(Playback())
        route.onPlayback(Playback(media = true, any = true))

        assertEquals(listOf(Mode.MUSIC, Mode.MUSIC), modes)
    }

    /** Activity destroy is exitCurMode (MainActivityUIBase.java:110 → :1105-1112): NULL only while the mode is ours (:8925). */
    @Test
    fun lastSessionGoneExitsMusicOnly() {
        route.onMediaSessions(present = true)
        route.onPlayback(Playback(media = true, any = true))
        route.onMediaSessions(present = false)

        assertEquals(listOf(Mode.MUSIC, Mode.NULL), modes)

        route.onMediaSessions(present = true)
        mcu.setMode(Mode.RADIO)
        modes.clear()
        route.onMediaSessions(present = false)

        assertTrue(modes.isEmpty())
    }

    // ---- BT music -------------------------------------------------------------------------

    /** A2DP playing is SRC_BTMUSIC (EvtModel.java:438-439); its AudioTrack is not a second player. */
    @Test
    fun btAudioSelectsBtMusicAndMasksMedia() {
        route.onBtAudio(playing = true)
        route.onPlayback(Playback(media = true, any = true))

        assertEquals(listOf(Mode.BT_MUSIC), modes)
    }

    // ---- projection -----------------------------------------------------------------------

    /** CONNECTED selects SRC_CARPLAY (ZlinkManage.java:246, :363-366); DISCONNECT exits to NULL. */
    @Test
    fun projectionConnectSelectsCarPlayAndDisconnectExits() {
        route.onProjection(connected = true)
        route.onProjection(connected = true)
        route.onProjection(connected = false)

        assertEquals(listOf(Mode.CARPLAY, Mode.NULL), modes)
    }

    /** exitCurMode is a no-op when another source took over (ZlinkManage.java:261, EventService.java:8925). */
    @Test
    fun disconnectLeavesAnotherSourceAlone() {
        route.onProjection(connected = true)
        mcu.setMode(Mode.RADIO)
        modes.clear()

        route.onProjection(connected = false)

        assertTrue(modes.isEmpty())
    }

    /** CarPlay's own audio is a media player: under projection it must not re-select MUSIC. */
    @Test
    fun mediaUnderProjectionLeavesCarPlay() {
        route.onProjection(connected = true)
        route.onPlayback(Playback(media = true, any = true))

        assertEquals(listOf(Mode.CARPLAY), modes)
    }

    /** DISCONNECT with a player still up hands the amp to it instead of leaving it on NULL. */
    @Test
    fun disconnectFallsBackToThePlayingSource() {
        route.onProjection(connected = true)
        route.onPlayback(Playback(media = true, any = true))
        route.onProjection(connected = false)

        assertEquals(listOf(Mode.CARPLAY, Mode.MUSIC), modes)
    }

    private companion object {
        /** `0D 0A LEN op ...`: the opcode is the first body byte. */
        const val OPCODE_INDEX = 3
        const val OP_SOUND_STATE = 0x3F
        const val OP_TIMED_MUTE = 0x4C
        const val OP_PLAY_STATE = 0x19
        const val TIMED_MUTE_UNITS = 20
    }
}
