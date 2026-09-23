package com.ripostelabs.carlauncher.carlib

import android.util.Log
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol.Mode
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * ArmAudioRoute — the vendor gateway's source switching for Android's own sound, on the owner path.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     PlaybackWatch (active players)        ──▶ onPlayback      ──▶ `3F nav system`; `01 0B` MUSIC / `01 0A` MOVIE
 *     BtCarKit A2DP sink playing            ──▶ onBtAudio       ──▶ `01 07` BT_MUSIC
 *     CarPlayState.connected (zlink status) ──▶ onProjection    ──▶ `01 20` SRC_CARPLAY / `01 63` SRC_NULL
 *     NowPlayingRepository.sources empty    ──▶ onMediaSessions ──▶ `01 63` SRC_NULL (exitCurMode)
 *
 * Android audio leaves the SoC into an amp the MCU switches. The tuner is the MCU's own, which is
 * why radio plays on Riposte OS 0.2 while everything from Android is silent until the MCU is told.
 * On stock the telling is spread over eventcenter and the media apps:
 *
 * - Every sound start or stop of any process (the zxw_io kernel uevent START_SND_PID /
 *   STOP_SND_PID) sends `3F nav system` with the system byte following "anything playing"
 *   (EventService.java:13551, :13576, :13606-13609, :8076-8083). Its log calls it "ARM mute".
 *   The nav byte is set while a navigation app's process plays (mapApkLst, :1237, :13545-13568):
 *   here a player with USAGE_ASSISTANCE_NAVIGATION_GUIDANCE. The MCU ducks its own tuner on it;
 *   the media apps duck through Android focus (notifyNavPlaySoundEvent, :8092-8096).
 * - musicplayer sends SRC_MUSIC when its player reaches state 3 and again on every resume, with no
 *   same-mode check (MusicPlayerService.java:121-125, :1097-1102, EventService.java:3933); pause keeps
 *   the mode (:474-478). Around play and pause it sends the timed mute `4C 14` (:1120) and the play
 *   state `19` (:531 → EventService.java:4327-4328). videoplayer is the same code with SRC_MOVIE
 *   (VideoPlayerService.java:1010). exitCurMode comes only from activity destroy or a final focus
 *   loss (MainActivityUIBase.java:110, MusicPlayerService.java:745-748 → :1105-1112) and sends
 *   SRC_NULL only while the valid mode is still theirs (EventService.java:8925-8939).
 * - btsuite selects SRC_BTMUSIC for an A2DP stream (EvtModel.java:438-439). On 0.2 the sink is
 *   the stock stack, whose AudioTrack is also a media player: BT masks the media trigger.
 * - Zlink's CONNECTED selects SRC_CARPLAY with the ACK wait (ZlinkManage.java:246, :363-366);
 *   DISCONNECT leaves it through exitCurMode (ZlinkManage.java:261-262). CarPlay's audio is a
 *   media player too, so projection masks the media trigger.
 *
 * Every input runs on [run], one thread, because a mode send waits for the MODE_ACK (up to
 * 3 × 500 ms): never the main looper. Tests pass an inline executor.
 *
 * ⚠ UNVERIFIED on the car: which frame opens the amp. Every send is logged under [LOG_TAG] with
 * its reason, so the next drive answers it from logcat.
 */
class ArmAudioRoute(private val mcu: Mcu, private val run: Executor = ownThread()) {

    /** What the route does to the port. [McuOwner] is the real one; tests substitute their own. */
    interface Mcu {
        val lastMode: Mode?

        fun setMode(mode: Mode): Boolean

        fun send(frame: ByteArray)
    }

    /** What is audible from Android right now, as [PlaybackWatch] reads the active players. */
    data class Playback(
        /** A music or video player: the vendor's SRC_MUSIC / SRC_MOVIE apps. */
        val media: Boolean = false,
        /** A media player with CONTENT_TYPE_MOVIE: the videoplayer's SRC_MOVIE. */
        val movie: Boolean = false,
        /** A navigation guidance player: the nav byte of `3F`. */
        val nav: Boolean = false,
        /** Any player at all: the system byte of `3F`, the vendor's mSystemPlay. */
        val any: Boolean = false,
    )

    private var playback = Playback()

    private var btAudio = false

    private var projected = false

    private var sessions = false

    /** The last `3F` pair sent; the vendor's mNaviPlay / mSystemPlay start false (EventService.java:461). */
    private var soundSent = Playback()

    /** The active player set changed; [what] names the players for the log. */
    fun onPlayback(now: Playback, what: String = "") = run.execute {
        val before = playback
        playback = now
        syncSound(what)

        if (now.media && !before.media) {
            mediaStarted(what)
        }
        if (!now.media && before.media) {
            mediaPaused()
        }
    }

    /** The A2DP sink started or stopped streaming. */
    fun onBtAudio(playing: Boolean) = run.execute {
        val before = btAudio
        btAudio = playing

        if (!playing || before) {
            return@execute
        }
        if (projected) {
            Log.i(LOG_TAG, "bt audio under projection: mode ${mcu.lastMode} left alone")
            return@execute
        }
        select(Mode.BT_MUSIC, "bt audio")
    }

    /** The projection session came up or went away. */
    fun onProjection(connected: Boolean) = run.execute {
        if (connected == projected) {
            return@execute
        }
        projected = connected

        // Connect: CarPlay becomes the valid source, as onStartCarPlayMode does.
        if (connected) {
            select(Mode.CARPLAY, "projection up")
            return@execute
        }

        // Disconnect: exitCurMode only while CarPlay is still ours; a later source stays.
        val current = mcu.lastMode
        if (current != Mode.CARPLAY) {
            Log.i(LOG_TAG, "projection down: mode is $current, left alone")
            return@execute
        }
        select(pending() ?: Mode.NULL, "projection down")
    }

    /** The launcher sees active media sessions, or none: none is the players' activity-destroy. */
    fun onMediaSessions(present: Boolean) = run.execute {
        val before = sessions
        sessions = present

        if (present || !before) {
            return@execute
        }
        val current = mcu.lastMode
        if (current != Mode.MUSIC && current != Mode.MOVIE) {
            Log.i(LOG_TAG, "sessions gone: mode is $current, left alone")
            return@execute
        }
        select(Mode.NULL, "sessions gone")
    }

    /** `3F nav system` whenever either byte changes. */
    private fun syncSound(what: String) {
        val now = Playback(nav = playback.nav, any = playback.any)
        if (now == soundSent) {
            return
        }

        soundSent = now
        mcu.send(McuOwnerProtocol.soundState(nav = now.nav, system = now.any))
        Log.i(LOG_TAG, "sent 3F nav=${bit(now.nav)} system=${bit(now.any)} (mode ${mcu.lastMode}) $what")
    }

    private fun mediaStarted(what: String) {
        mcu.send(McuOwnerProtocol.timedMute())
        mcu.send(McuOwnerProtocol.playState(playing = true))

        // Under projection or a BT stream the player IS that source's audio.
        if (projected || btAudio) {
            Log.i(LOG_TAG, "media start under ${mcu.lastMode}: left alone $what")
            return
        }
        select(mediaMode(), "media start $what")
    }

    private fun mediaPaused() {
        mcu.send(McuOwnerProtocol.timedMute())
        mcu.send(McuOwnerProtocol.playState(playing = false))
    }

    /** The source still up once CarPlay leaves, in the vendor's priority. */
    private fun pending(): Mode? = when {
        btAudio -> Mode.BT_MUSIC
        playback.media -> mediaMode()
        else -> null
    }

    private fun mediaMode(): Mode = if (playback.movie) Mode.MOVIE else Mode.MUSIC

    private fun select(mode: Mode, why: String) {
        val acked = mcu.setMode(mode)
        Log.i(LOG_TAG, "$why: sent mode $mode, acked=$acked")
    }

    private fun bit(on: Boolean) = if (on) 1 else 0

    /** [Mcu] over the owner. */
    private class OwnerMcu(private val owner: McuOwner) : Mcu {
        override val lastMode: Mode?
            get() = owner.lastMode

        override fun setMode(mode: Mode) = owner.setMode(mode)

        override fun send(frame: ByteArray) = owner.send(frame)
    }

    companion object {
        const val LOG_TAG = "ArmAudioRoute"

        fun forOwner(owner: McuOwner): ArmAudioRoute = ArmAudioRoute(OwnerMcu(owner))

        private fun ownThread(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "arm-audio-route").apply { isDaemon = true }
        }
    }
}
