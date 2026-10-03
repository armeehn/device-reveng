package com.ripostelabs.carlauncher.carlib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread

/**
 * AmpVolumeKeys — the launcher's volume keys as amp steps, with Android's stream held still.
 *
 *     nav bar, car service bar, wheel ──▶ NavVolume.ACTION_STEP ±1 ──▶ amp 12 + 1 ──▶ 05 05 13
 *     anything else ──▶ STREAM_MUSIC 24 → 25 ──▶ VOLUME_CHANGED ──▶ STREAM_MUSIC back to 24
 *
 * The amp behind the MCU is the car's only volume; Android's stream stays pinned one step below
 * its top so it barely attenuates. A stream move is never an amp step: the iPhone's AVRCP
 * absolute volume sets it on its own and raised the amp a level (car, 2026-10-02 07:09:30).
 * A move that lands on the pin needs no reset, so the reset cannot loop. Both receivers run on
 * their own thread, so the pin comes back at once and not behind the launcher's UI. The base is
 * the MCU's last `79`, so the Quick controls slider and these keys share one level.
 */
class AmpVolumeKeys(private val setAmp: (Int) -> Unit, startLevel: Int) : McuOwner.Listener {

    private val lock = Any()
    private var level = startLevel

    @Volatile
    private var audio: AudioManager? = null
    private var thread: HandlerThread? = null

    private val streamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onVolumeChanged(intent)
        }
    }

    private val stepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            step(intent.getIntExtra(NavVolume.EXTRA_DELTA, 0))
        }
    }

    /** Pins the stream and starts listening. Owner path only: stock routes keys to the amp itself. */
    fun start(context: Context) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        audio = manager
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, pinIndex(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)

        val worker = HandlerThread(THREAD_NAME).apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        context.registerReceiver(streamReceiver, IntentFilter(VOLUME_CHANGED_ACTION), null, handler, Context.RECEIVER_NOT_EXPORTED)
        // Not exported: this app and the system uid (the car service's bar) only.
        context.registerReceiver(stepReceiver, IntentFilter(NavVolume.ACTION_STEP), null, handler, Context.RECEIVER_NOT_EXPORTED)
    }

    fun stop(context: Context) {
        if (audio == null) {
            return
        }

        context.unregisterReceiver(streamReceiver)
        context.unregisterReceiver(stepReceiver)
        thread?.quitSafely()
        thread = null
        audio = null
    }

    /** The MCU's report is the truth; our own sends only bridge the gap until it arrives. */
    override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) {
        synchronized(lock) { level = volume.level }
    }

    /** One key press: [delta] amp levels from the last known one. */
    fun step(delta: Int) {
        if (delta == 0) {
            return
        }

        val target = synchronized(lock) {
            level = (level + delta).coerceIn(0, CarService.MAX_VOLUME)
            level
        }
        setAmp(target)
    }

    /** One STREAM_MUSIC move [from] → [to] on a stream of [max] steps: true when it must go back to the pin. */
    fun onStreamMoved(from: Int, to: Int, max: Int): Boolean = from != to && to != pinIndex(max)

    private fun onVolumeChanged(intent: Intent) {
        val manager = audio ?: return
        if (intent.getIntExtra(EXTRA_STREAM_TYPE, NO_VALUE) != AudioManager.STREAM_MUSIC) {
            return
        }

        val from = intent.getIntExtra(EXTRA_PREV_VALUE, NO_VALUE)
        val to = intent.getIntExtra(EXTRA_VALUE, NO_VALUE)
        if (from == NO_VALUE || to == NO_VALUE) {
            return
        }

        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (onStreamMoved(from, to, max)) {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, pinIndex(max), 0)
        }
    }

    companion object {
        // AudioManager's own names for these are @hide.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        private const val NO_VALUE = -1
        private const val THREAD_NAME = "amp-volume"

        fun pinIndex(max: Int): Int = max - 1
    }
}
