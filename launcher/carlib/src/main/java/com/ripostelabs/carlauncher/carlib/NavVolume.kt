package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.media.AudioManager

/**
 * RAV4-177: the nav bar's Vol - and Vol + keys, as stock's floating ball has
 * (WindowTouchHelpLandView.java:60-94).
 *
 *     nav key ──▶ STREAM_MUSIC one step ──▶ AmpVolumeKeys ──▶ amp level ± 1 ──▶ 79 ──▶ VolumePopup
 *
 * One Android step, the same path the steering-wheel and `input keyevent` volume take, so the
 * launcher's bar and the car service's bar need no MCU access of their own and share one level
 * with the Quick controls slider. On the stock image the vendor routes the stream to the amp.
 */
object NavVolume {

    enum class Step { UP, DOWN }

    /** No Android dialog: the MCU's volume report pops the launcher's own window. */
    const val FLAGS = 0

    fun direction(step: Step): Int = when (step) {
        Step.UP -> AudioManager.ADJUST_RAISE
        Step.DOWN -> AudioManager.ADJUST_LOWER
    }

    fun step(context: Context, step: Step) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction(step), FLAGS)
    }
}
