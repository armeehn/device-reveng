package com.ripostelabs.carlauncher.carlib

import android.media.AudioManager
import java.util.concurrent.Executor

/**
 * RadioFocusChange — what an Android audio-focus change does to the tuner source, on the owner path.
 *
 *     AudioManager ──(main looper)──▶ on(change) ──▶ [run] ──▶ RadioSource / release   `01 xx`, ACK awaited
 *
 * The vendor radio's AudioManagerUtils (AudioManagerUtils.java:61-88): a permanent loss hands the
 * source back, a transient loss ducks the tuner with `42 01`, a gain re-sends the mode.
 *
 * Focus callbacks arrive on the main looper, and a mode send waits for the MCU's MODE_ACK, up to
 * 3 × 500 ms. The MCU never acknowledges SRC_NULL, so every release waited the full 1.5 s on the
 * main thread; a loss, a gain and a wheel press queued behind each other passed the 5 s input
 * limit and HOME showed "not responding" (RAV4-147). Every change therefore runs on [run].
 */
class RadioFocusChange(
    private val source: RadioSource,
    /** CarService.releaseRadio: the source back and our focus request dropped. */
    private val release: () -> Unit,
    private val run: Executor,
) {

    fun on(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> run.execute { release() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> run.execute { source.duck(true) }
            AudioManager.AUDIOFOCUS_GAIN -> run.execute { reclaim() }
        }
    }

    private fun reclaim() {
        if (!source.held) {
            return
        }

        source.claim()
    }
}
