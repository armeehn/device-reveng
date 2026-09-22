package com.ripostelabs.carlauncher.carlib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * AmpVolumeKeys — Android's volume (keys, the system dialog, `input keyevent`) as amp steps.
 *
 *     key ──▶ AudioService: STREAM_MUSIC 24 → 25 ──▶ VOLUME_CHANGED ──▶ amp 12 + 1 ──▶ 05 05 13
 *                                                                  └──▶ STREAM_MUSIC back to 24
 *
 * The amp behind the MCU is the car's only volume; Android's stream stays pinned one step below
 * its top so it barely attenuates, and each move away from the pin is one amp level per index.
 * One below, not the top: at the top a VOLUME_UP changes nothing and AudioService broadcasts
 * nothing. A move that lands on the pin is never a step, so the reset cannot loop. The base is
 * the MCU's last `79`, so the Quick controls slider and these keys share one level.
 */
class AmpVolumeKeys(private val setAmp: (Int) -> Unit, startLevel: Int) : McuOwner.Listener {

    private val lock = Any()
    private var level = startLevel
    private var audio: AudioManager? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onVolumeChanged(intent)
        }
    }

    /** Pins the stream and starts listening. Owner path only: stock routes keys to the amp itself. */
    fun start(context: Context) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        audio = manager
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, pinIndex(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
        context.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED_ACTION), Context.RECEIVER_NOT_EXPORTED)
    }

    fun stop(context: Context) {
        if (audio == null) {
            return
        }

        context.unregisterReceiver(receiver)
        audio = null
    }

    /** The MCU's report is the truth; our own sends only bridge the gap until it arrives. */
    override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) {
        synchronized(lock) { level = volume.level }
    }

    /**
     * One STREAM_MUSIC move [from] → [to] on a stream of [max] steps. Sends the amp level and
     * returns true when the stream must go back to the pin.
     */
    fun onStreamMoved(from: Int, to: Int, max: Int): Boolean {
        if (to == pinIndex(max)) {
            return false
        }

        val target = synchronized(lock) {
            level = (level + to - from).coerceIn(0, CarService.MAX_VOLUME)
            level
        }
        setAmp(target)
        return true
    }

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

        fun pinIndex(max: Int): Int = max - 1
    }
}
