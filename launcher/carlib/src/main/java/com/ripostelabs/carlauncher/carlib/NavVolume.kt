package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.content.Intent
import android.media.AudioManager

/**
 * RAV4-177: the nav bar's Vol - and Vol + keys, as stock's floating ball has
 * (WindowTouchHelpLandView.java:60-94). The wheel keys take the same path.
 *
 *     AMP     key ──▶ ACTION_STEP ±1 (to the launcher) ──▶ AmpVolumeKeys ──▶ 05 05 v ──▶ 79 ──▶ VolumePopup
 *     STREAM  key ──▶ STREAM_MUSIC one step ──▶ the vendor routes it to the amp (stock image)
 *
 * AMP never moves Android's stream. A stream step went out and came back through a broadcast
 * on the launcher's main thread, 200 ms or more a step: a run of presses walked STREAM_MUSIC
 * 24 → 17 before the pin came back, and the music dipped and jumped (car, 2026-10-01 15:03:36).
 * The broadcast is explicit and not exported; the car service runs as the system uid, which a
 * not-exported receiver still hears, so both bars share one level with the Quick controls slider.
 */
object NavVolume {

    enum class Step { UP, DOWN }

    /** AMP: Riposte OS, where [AmpVolumeKeys] owns the amp. STREAM: the stock image. */
    enum class Route { AMP, STREAM }

    const val ACTION_STEP = "com.ripostelabs.carlauncher.action.VOLUME_STEP"
    const val EXTRA_DELTA = "delta"

    /** No Android dialog: the MCU's volume report pops the launcher's own window. */
    const val FLAGS = 0

    fun delta(step: Step): Int = when (step) {
        Step.UP -> 1
        Step.DOWN -> -1
    }

    fun direction(step: Step): Int = when (step) {
        Step.UP -> AudioManager.ADJUST_RAISE
        Step.DOWN -> AudioManager.ADJUST_LOWER
    }

    /** One step on [route]; AMP goes to the launcher in [launcher], this app unless named. */
    fun step(context: Context, step: Step, route: Route, launcher: String = context.packageName) {
        if (route == Route.AMP) {
            context.sendBroadcast(Intent(ACTION_STEP).setPackage(launcher).putExtra(EXTRA_DELTA, delta(step)))
            return
        }

        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction(step), FLAGS)
    }
}
