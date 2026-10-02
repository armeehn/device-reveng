package com.ripostelabs.carlauncher.carlib

import android.util.Log

/**
 * CallSound — the DSP plays a call as speech, then gives the music its sound back.
 *
 * ```
 *  CarPlay call (PHONE_CALL_ON) ┐
 *  Bluetooth call (HFP)         ┴─ onCall(true)  ──▶ speech(): 4F 10 … 4F 0F ──▶ MCU
 *                                  onCall(false) ──▶ saved(setup): the store's own blocks
 * ```
 *
 * Speech is the saved sound with the music shaping taken out: a flat EQ with a mild presence
 * lift, no loudness, bass boost, subwoofer or surround, the rear pair muted and the front cut
 * below [SPEECH_HP_HZ], where a call carries no voice. Nothing is persisted: the restore
 * re-sends what [McuSetupStore] holds at that moment, so an EQ edit made during the call
 * comes back too. Boot and wake send the saved blocks as before ([McuOwnerProtocol.vendorInit]).
 */
class CallSound(
    private val send: (ByteArray) -> Unit,
    private val setup: () -> McuSetup?,
) {

    private var inCall = false

    /** Either call source changed; only an edge sends. */
    fun onCall(active: Boolean) {
        if (active == inCall) {
            return
        }
        inCall = active

        val blocks = if (active) speech(setup()) else saved(setup())
        blocks.forEach(send)
        Log.i(TAG, if (active) "call: speech profile sent" else "call over: saved sound restored")
    }

    companion object {
        private const val TAG = "CallSound"

        /** Front high-pass during a call: telephone voice has nothing below 100 Hz but road boom. */
        const val SPEECH_HP_HZ = 100

        /** dB on the 2..3.8 kHz bands (DspEq.FREQUENCIES 30..34): consonants, intelligibility. */
        val PRESENCE: Map<Int, Int> = mapOf(30 to 2, 31 to 3, 32 to 3, 33 to 3, 34 to 2)

        /** Channel gain that silences an output: 0 on the 0..95 slider is -80 dB. */
        private const val MUTED = 0

        /** Subwoofer gain floor, -12 dB on the 0..24 scale; the sub carries no voice. */
        private const val SUB_OFF = 0

        /** The call's EQ: flat plus [PRESENCE]. */
        val SPEECH_EQ: List<Int> = DspEq.FLAT.mapIndexed { band, g -> g + (PRESENCE[band] ?: 0) }

        /**
         * The eight sound blocks, in the DSP app's boot order (DspService.java:64-83), carrying
         * the saved sound; flat defaults with no table.
         */
        fun saved(setup: McuSetup?): List<ByteArray> = blocks(
            eq = setup?.dspEq ?: DspEq.FLAT,
            loud = setup?.dspLoud ?: false,
            crossover = setup?.dspCrossover ?: DspSound.Crossover(),
            sub = setup?.dspSub ?: DspSound.Sub(),
            field = setup?.dspField ?: DspSound.Field(),
            bass = setup?.dspBass ?: DspSound.Bass(),
            surround = setup?.dspSurround ?: DspSound.Surround(),
        )

        /** The same eight blocks shaped for a voice; delays stay as saved. */
        fun speech(setup: McuSetup?): List<ByteArray> {
            val field = setup?.dspField ?: DspSound.Field()
            val front = field
                .withGain(DspSound.Speaker.LEFT_FRONT, DspSound.GAIN_FLAT)
                .withGain(DspSound.Speaker.RIGHT_FRONT, DspSound.GAIN_FLAT)
                .withGain(DspSound.Speaker.LEFT_REAR, MUTED)
                .withGain(DspSound.Speaker.RIGHT_REAR, MUTED)
                .withGain(DspSound.Speaker.CENTRE, DspSound.GAIN_FLAT)

            return blocks(
                eq = SPEECH_EQ,
                loud = false,
                crossover = DspSound.Crossover(frontHp = SPEECH_HP_HZ),
                sub = (setup?.dspSub ?: DspSound.Sub()).copy(gain = SUB_OFF),
                field = front,
                bass = DspSound.Bass(),
                surround = DspSound.Surround(),
            )
        }

        private fun blocks(
            eq: List<Int>,
            loud: Boolean,
            crossover: DspSound.Crossover,
            sub: DspSound.Sub,
            field: DspSound.Field,
            bass: DspSound.Bass,
            surround: DspSound.Surround,
        ): List<ByteArray> = listOf(
            McuSetupProtocol.dspEq(eq),
            McuSetupProtocol.dspLoud(loud),
            McuSetupProtocol.dspCrossover(crossover),
            McuSetupProtocol.dspSub(sub),
            McuSetupProtocol.dspDelay(field),
            McuSetupProtocol.dspChannelGain(field),
            McuSetupProtocol.dspBass(bass),
            McuSetupProtocol.dspSurround(surround),
        )
    }
}
