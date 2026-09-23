package com.ripostelabs.carlauncher.carlib

import android.util.Log

/**
 * ArmAudioRoute — what the vendor gateway told the MCU about Android's own sound, on the owner path.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     PlaybackWatch (any AudioTrack started) ──▶ onAndroidSound ──▶ `3F 00 01` / `3F 00 00`
 *     CarPlayState.connected (zlink status)  ──▶ onProjection   ──▶ `01 20` SRC_CARPLAY / `01 63` SRC_NULL
 *
 * Android audio leaves the SoC into an amp the MCU switches. The tuner is the MCU's own, which is
 * why radio plays on Riposte OS 0.2 while CarPlay is silent. On stock, eventcenter sends two
 * things the launcher never did:
 *
 * - Every sound start or stop of any process (the zxw_io kernel uevent START_SND_PID /
 *   STOP_SND_PID) sends `3F nav system` with the system byte following "anything playing"
 *   (EventService.java:13551, :13576, :13606-13609, :8076-8083). Its log calls it "ARM mute".
 * - Zlink's CONNECTED selects SRC_CARPLAY with the ACK wait (ZlinkManage.java:246, :363-366);
 *   DISCONNECT leaves it through exitCurMode, a no-op unless CARPLAY is still the valid mode
 *   (ZlinkManage.java:261-262, EventService.java:8925-8939).
 *
 * ⚠ UNVERIFIED on the car: which of the two opens the path (or both). Every send is logged under
 * [LOG_TAG] with its reason, so the next drive answers it from logcat.
 */
class ArmAudioRoute(private val mcu: Mcu) {

    /** What the route does to the port. [McuOwner] is the real one; tests substitute their own. */
    interface Mcu {
        val lastMode: McuOwnerProtocol.Mode?

        fun setMode(mode: McuOwnerProtocol.Mode): Boolean

        fun send(frame: ByteArray)
    }

    /** The vendor's mSystemPlay, false until something plays (EventService.java:461). */
    private var sound = false

    private var projected = false

    /**
     * Android playback went from silent to playing or back; [what] names the players for the log.
     * One caller thread ([PlaybackWatch]'s main looper), so it never waits on [onProjection]'s ACK.
     */
    fun onAndroidSound(playing: Boolean, what: String = "") {
        if (playing == sound) {
            return
        }

        sound = playing
        mcu.send(McuOwnerProtocol.soundState(nav = false, system = playing))
        Log.i(LOG_TAG, "sent 3F system=${if (playing) 1 else 0} (mode ${mcu.lastMode}) $what")
    }

    /** The projection session came up or went away. Blocks for the MODE_ACK: never on main. */
    @Synchronized
    fun onProjection(connected: Boolean) {
        if (connected == projected) {
            return
        }

        projected = connected

        // Connect: CarPlay becomes the valid source, as onStartCarPlayMode does.
        if (connected) {
            val acked = mcu.setMode(McuOwnerProtocol.Mode.CARPLAY)
            Log.i(LOG_TAG, "projection up: sent mode CARPLAY, acked=$acked")
            return
        }

        // Disconnect: exitCurMode only while CarPlay is still ours; a later source stays.
        val current = mcu.lastMode
        if (current != McuOwnerProtocol.Mode.CARPLAY) {
            Log.i(LOG_TAG, "projection down: mode is $current, left alone")
            return
        }
        val acked = mcu.setMode(McuOwnerProtocol.Mode.NULL)
        Log.i(LOG_TAG, "projection down: sent mode NULL, acked=$acked")
    }

    /** [Mcu] over the owner. */
    private class OwnerMcu(private val owner: McuOwner) : Mcu {
        override val lastMode: McuOwnerProtocol.Mode?
            get() = owner.lastMode

        override fun setMode(mode: McuOwnerProtocol.Mode) = owner.setMode(mode)

        override fun send(frame: ByteArray) = owner.send(frame)
    }

    companion object {
        const val LOG_TAG = "ArmAudioRoute"

        fun forOwner(owner: McuOwner): ArmAudioRoute = ArmAudioRoute(OwnerMcu(owner))
    }
}
