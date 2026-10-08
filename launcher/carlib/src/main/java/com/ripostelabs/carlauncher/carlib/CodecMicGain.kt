package com.ripostelabs.carlauncher.carlib

import android.util.Log

/**
 * CodecMicGain — the codec's analog mic gain, raised from the vendor's value at launcher start.
 *
 *     launcher start (root) ──▶ tinymix "ADC1..3 Volume" = [VOLUME] ──▶ every capture, every app
 *
 * The vendor's mixer_paths sets ADC1-3 Volume to 8 of 0..20 at audio HAL start, and the mic
 * routes never set it again. At 8 speech reached the phone near -47 dBFS and a caller's voicemail
 * held nothing a transcriber could hear (bench, 2026-10-07): the "underwater" CarPlay calls, on
 * stock firmware too. Raising the value lifted every capture source by 12 to 24 dB on the bench
 * (bench-micgain, 16:28). [VOLUME] is the codec's top, 20: about +18 dB at its 1.5 dB step, the
 * owner's pick (2026-10-07). Room noise alone peaked at -4 dBFS there, so loud speech can clip;
 * RNNoise and the echo canceller run after it.
 *
 * The value holds until the audio HAL restarts, which reapplies the vendor's 8; a launcher start
 * follows any reboot, so this covers the normal path.
 */
object CodecMicGain {

    /** 0..20 on this codec ([MAX_VOLUME]); the vendor's value is [VENDOR_VOLUME]. */
    const val VOLUME = 20
    const val MAX_VOLUME = 20
    const val VENDOR_VOLUME = 8

    private const val TAG = "CodecMicGain"
    private val ADCS = listOf(1, 2, 3)

    /** One tinymix per ADC, e.g. `tinymix "ADC1 Volume" 16`. */
    fun commands(volume: Int): List<String> = ADCS.map { "tinymix \"ADC$it Volume\" $volume" }

    fun apply(shell: (String) -> RootShell.Result = { RootShell.exec(it) }, volume: Int = VOLUME) {
        val failed = commands(volume).filterNot { shell(it).ok }
        if (failed.isEmpty()) {
            Log.i(TAG, "mic ADC gain set to $volume")
            return
        }
        Log.w(TAG, "mic ADC gain not set: $failed")
    }
}
