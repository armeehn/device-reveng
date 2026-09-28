package com.ripostelabs.carlauncher.carlib

/**
 * The reverse camera's PR2000 decoder through the car service (ICarService API >= 4). Every call
 * says whether the service answered, so the caller can fall back to its root shell.
 */
interface CarDecoder {
    /** Set the decoder row (0 auto .. 8); false when no service took it. */
    fun setDecoderMode(mode: Int): Boolean

    /** True when the decoder has a signal the AIS server can size; null when no service answered. */
    fun decoderLocked(): Boolean?

    /** One ICarService.DECODER_* nudge; false when no service took it. */
    fun decoderSignal(action: Int): Boolean
}
