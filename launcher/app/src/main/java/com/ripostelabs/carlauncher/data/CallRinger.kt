package com.ripostelabs.carlauncher.data

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * RAV4-152 — the car's own ringtone while a call rings and the phone sends no in-band ring.
 *
 * The phone's in-band ring rides the SCO link; many phones send none to a car kit, and btsuite
 * then rang locally. [set] true waits [GRACE_MS] first, so an in-band ring that follows the
 * AG_CALL_CHANGED broadcast closely ([IncomingCallGate][com.ripostelabs.carlauncher.carlib.IncomingCallGate]
 * turns ring off on SCO up) never overlaps the local one. Loops until [set] false.
 * UNVERIFIED on the car: that the ring usage reaches the ARM amp path during the HFP state.
 */
class CallRinger(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private val start = Runnable { play() }
    private var armed = false

    fun set(on: Boolean) {
        if (on == armed) {
            return
        }
        armed = on
        main.removeCallbacks(start)

        if (on) {
            main.postDelayed(start, GRACE_MS)
            return
        }
        ringtone?.stop()
        ringtone = null
    }

    private fun play() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val r = RingtoneManager.getRingtone(appContext, uri) ?: run {
            Log.w(TAG, "no ringtone to play")
            return
        }
        r.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        r.isLooping = true
        r.play()
        ringtone = r
        Log.i(TAG, "local ringtone on")
    }

    private companion object {
        const val TAG = "CallRinger"
        /** RAV4-152: how long an in-band ring gets to arrive before the car rings itself. */
        const val GRACE_MS = 1_000L
    }
}
