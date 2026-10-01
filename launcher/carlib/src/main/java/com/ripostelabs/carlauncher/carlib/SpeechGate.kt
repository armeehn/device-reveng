package com.ripostelabs.carlauncher.carlib

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/**
 * SpeechGate (`riposte-vad/1`): does a window of cabin audio contain a human voice?
 *
 * The automatic road-noise capture writes a window to flash, and later uploads it, only when this
 * says no. Any doubt is a yes: a missed noise window costs nothing, a missed word is a privacy leak.
 *
 * Pure Kotlin, no native code, so the x86_64 emulator runs the same gate as the car. Per 20 ms
 * frame of the window, at 16 kHz after decimating the 48 kHz input, two detectors look for a voice
 * and either one is enough:
 *
 *     voicing   max normalised autocorrelation over pitch lags 70-400 Hz on a 40 ms Hann window.
 *               Vowels are periodic; road roar is not.
 *     level     band energy against the window's own 20th-percentile floor.
 *     swing     max - min of that energy within +-200 ms. Speech rises and falls with syllables;
 *               an engine or a blower holds steady even when it is tonal.
 *
 *     A (road)  voicing on the 150 Hz high-passed, pre-emphasised signal >= 0.45,
 *               level 300-3400 Hz >= floor + 4 dB, swing >= 12 dB; 3 frames in a row or 6 in all
 *     B (fan)   voicing on a 100-1000 Hz band-pass >= 0.5,
 *               level 200-1000 Hz >= floor + 6 dB, swing >= 12 dB; 3 frames in a row or 10 in all
 *
 * A alone misses a voice under broadband blower noise, whose hiss the pre-emphasis lifts; B looks
 * where voiced harmonics stand above such hiss. Calibrated on LibriVox readers mixed into CC0
 * car-interior recordings and synthetic rumble, hiss, pink fan and engine (UPLINK.md has the table):
 * one second of speech at 0 dB SNR is caught 100 % of the time in road noise, hiss and pink fan,
 * and half the time under a strongly tonal synthetic blower. The capture also holds off for a
 * minute after any speech window, because a conversation rarely fits in one.
 */
class SpeechGate(private val config: Config = Config()) {

    data class Config(
        val voicing: Double = 0.45,
        val aboveFloorDb: Double = 4.0,
        val swingDb: Double = 12.0,
        val runFrames: Int = 3,
        val totalFrames: Int = 6,
        val fanVoicing: Double = 0.5,
        val fanAboveFloorDb: Double = 6.0,
        val fanSwingDb: Double = 12.0,
        val fanRunFrames: Int = 3,
        val fanTotalFrames: Int = 10,
        val floorPercentile: Double = 20.0,
    )

    /** One detector's thresholds (see the class comment). */
    private data class Rule(val voicing: Double, val aboveFloorDb: Double, val swingDb: Double, val run: Int, val total: Int)

    /** [frames] judged, how many looked like speech, the longest run of them, and the call. */
    data class Verdict(val frames: Int, val speechFrames: Int, val longestRun: Int, val speech: Boolean)

    private val window = DoubleArray(WIN) { 0.5 - 0.5 * cos(2 * PI * it / (WIN - 1)) }
    private val fft = Fft(NFFT)
    private val re = DoubleArray(NFFT)
    private val im = DoubleArray(NFFT)

    /** Judge [length] samples of 48 kHz mono PCM16. Not thread-safe: one gate per thread. */
    fun judge(pcm48k: ShortArray, length: Int = pcm48k.size): Verdict {
        val x = decimate(pcm48k, length)
        val n = x.size / FRAME
        if (n == 0) {
            return Verdict(0, 0, 0, false)
        }

        val (hp, pe) = filters(x)
        val bp = bandPass(x)
        val levelRoad = DoubleArray(n)
        val levelFan = DoubleArray(n)
        val voicingRoad = DoubleArray(n)
        val voicingFan = DoubleArray(n)
        for (i in 0 until n) {
            bandLevels(hp, i, levelRoad, levelFan)
            voicingRoad[i] = periodicity(pe, i)
            voicingFan[i] = periodicity(bp, i)
        }
        val road = decide(levelRoad, voicingRoad, roadRule)
        val fan = decide(levelFan, voicingFan, fanRule)
        val speech = road.speech || fan.speech
        return Verdict(n, maxOf(road.speechFrames, fan.speechFrames), maxOf(road.longestRun, fan.longestRun), speech)
    }

    private val roadRule = Rule(config.voicing, config.aboveFloorDb, config.swingDb, config.runFrames, config.totalFrames)
    private val fanRule = Rule(config.fanVoicing, config.fanAboveFloorDb, config.fanSwingDb, config.fanRunFrames, config.fanTotalFrames)

    /** 4th-order Butterworth 100-1000 Hz band-pass at 16 kHz, as second-order sections. */
    private fun bandPass(x: DoubleArray): DoubleArray {
        var y = x
        for (sec in BAND_PASS_SOS) {
            val out = DoubleArray(y.size)
            var z1 = 0.0
            var z2 = 0.0
            for (i in y.indices) {
                val v = sec[0] * y[i] + z1
                z1 = sec[1] * y[i] - sec[4] * v + z2
                z2 = sec[2] * y[i] - sec[5] * v
                out[i] = v
            }
            y = out
        }
        return y
    }

    // --- signal path ---------------------------------------------------------------------

    /** 48 kHz to 16 kHz: a three-sample average, then every third sample. Scaled to +-1. */
    private fun decimate(pcm: ShortArray, length: Int): DoubleArray {
        val n = length / DECIMATE
        return DoubleArray(n) { i ->
            val k = i * DECIMATE
            (pcm[k] + pcm[k + 1] + pcm[k + 2]) / (DECIMATE * FULL_SCALE)
        }
    }

    /** One-pole 150 Hz high-pass (road rumble out), then a 0.97 pre-emphasis for the pitch track. */
    private fun filters(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val a = exp(-2 * PI * HIGH_PASS_HZ / RATE)
        val hp = DoubleArray(x.size)
        var prevX = 0.0
        var prevY = 0.0
        for (i in x.indices) {
            prevY = a * (prevY + x[i] - prevX)
            prevX = x[i]
            hp[i] = prevY
        }
        val pe = DoubleArray(x.size)
        pe[0] = hp[0]
        for (i in 1 until x.size) {
            pe[i] = hp[i] - PRE_EMPHASIS * hp[i - 1]
        }
        return hp to pe
    }

    /** The 40 ms Hann-windowed analysis block ending at frame [i], zero-padded to the FFT size. */
    private fun load(signal: DoubleArray, i: Int) {
        re.fill(0.0)
        im.fill(0.0)
        val end = (i + 1) * FRAME
        for (k in 0 until WIN) {
            val s = end - WIN + k
            if (s >= 0) {
                re[k] = signal[s] * window[k]
            }
        }
    }

    /** Energy in the speech band (road) and the low voice band (fan) for frame [i], in dB. */
    private fun bandLevels(hp: DoubleArray, i: Int, road: DoubleArray, fan: DoubleArray) {
        load(hp, i)
        fft.forward(re, im)
        road[i] = 10 * log10(power(BAND_LO_BIN, BAND_HI_BIN) + EPS)
        fan[i] = 10 * log10(power(FAN_LO_BIN, FAN_HI_BIN) + EPS)
    }

    private fun power(from: Int, to: Int): Double {
        var sum = 0.0
        for (k in from..to) {
            sum += re[k] * re[k] + im[k] * im[k]
        }
        return sum
    }

    /** Peak normalised autocorrelation over pitch lags, via the power spectrum. */
    private fun periodicity(pe: DoubleArray, i: Int): Double {
        load(pe, i)
        fft.forward(re, im)
        for (k in 0 until NFFT) {
            re[k] = re[k] * re[k] + im[k] * im[k]
            im[k] = 0.0
        }
        fft.inverse(re, im)
        val zero = re[0]
        if (zero <= EPS) {
            return 0.0
        }
        var best = 0.0
        for (lag in LAG_MIN..LAG_MAX) {
            val r = re[lag] / zero * WIN / (WIN - lag)
            if (r > best) {
                best = r
            }
        }
        return best
    }

    private fun decide(level: DoubleArray, voicing: DoubleArray, rule: Rule): Verdict {
        val n = level.size
        val floor = percentile(level, config.floorPercentile)
        var run = 0
        var longest = 0
        var total = 0
        for (i in 0 until n) {
            val swing = swing(level, i)
            val speech = voicing[i] >= rule.voicing &&
                level[i] >= floor + rule.aboveFloorDb &&
                swing >= rule.swingDb
            if (!speech) {
                run = 0
                continue
            }
            run++
            total++
            longest = maxOf(longest, run)
        }
        val verdict = longest >= rule.run || total >= rule.total
        return Verdict(n, total, longest, verdict)
    }

    private fun swing(level: DoubleArray, i: Int): Double {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (k in maxOf(0, i - SWING_HALF)..minOf(level.size - 1, i + SWING_HALF)) {
            lo = minOf(lo, level[k])
            hi = maxOf(hi, level[k])
        }
        return hi - lo
    }

    /** Linear-interpolated percentile, as numpy computes it (the calibration used numpy). */
    private fun percentile(values: DoubleArray, p: Double): Double {
        val sorted = values.sortedArray()
        val pos = p / 100 * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /** In-place iterative radix-2 FFT for one fixed size. */
    private class Fft(private val n: Int) {
        private val bits = Integer.numberOfTrailingZeros(n)
        private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
        private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }

        fun forward(re: DoubleArray, im: DoubleArray) = run(re, im, SIGN_FORWARD)

        /** Inverse, scaled by 1/n so that inverse(forward(x)) == x. */
        fun inverse(re: DoubleArray, im: DoubleArray) {
            run(re, im, SIGN_INVERSE)
            for (k in 0 until n) {
                re[k] /= n
                im[k] /= n
            }
        }

        private fun run(re: DoubleArray, im: DoubleArray, sign: Double) {
            for (i in 0 until n) {
                val j = Integer.reverse(i) ushr (Int.SIZE_BITS - bits)
                if (j <= i) {
                    continue
                }
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
            var size = 2
            while (size <= n) {
                val step = n / size
                for (start in 0 until n step size) {
                    for (k in 0 until size / 2) {
                        val wr = cosT[k * step]
                        val wi = -sign * sinT[k * step]
                        val a = start + k
                        val b = a + size / 2
                        val tr = re[b] * wr - im[b] * wi
                        val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                    }
                }
                size *= 2
            }
        }

        private companion object {
            const val SIGN_FORWARD = 1.0
            const val SIGN_INVERSE = -1.0
        }
    }

    companion object {
        /** Written into every sidecar, so the trainer knows which gate passed a window. */
        const val METHOD = "riposte-vad/1"

        const val INPUT_RATE = 48_000
        private const val DECIMATE = 3
        private const val RATE = INPUT_RATE / DECIMATE
        private const val FULL_SCALE = 32768.0
        private const val FRAME = RATE / 50          // 20 ms
        private const val WIN = 2 * FRAME            // 40 ms analysis block
        private const val NFFT = 1024
        private const val HIGH_PASS_HZ = 150.0
        private const val PRE_EMPHASIS = 0.97
        private const val LAG_MIN = RATE / 400       // 400 Hz voice
        private const val LAG_MAX = RATE / 70        // 70 Hz voice
        private const val BAND_LO_BIN = 20           // ceil(300 Hz / 15.625 Hz)
        private const val BAND_HI_BIN = 217          // floor(3400 Hz / 15.625 Hz)
        private const val FAN_LO_BIN = 13            // ceil(200 Hz / 15.625 Hz)
        private const val FAN_HI_BIN = 64            // 1000 Hz / 15.625 Hz

        /** scipy.signal.butter(4, [100, 1000], "band", fs=16000, output="sos"): b0 b1 b2 a0 a1 a2. */
        private val BAND_PASS_SOS = arrayOf(
            doubleArrayOf(0.0006390282088877586, 0.0012780564177755172, 0.0006390282088877586, 1.0, -1.4765539405623134, 0.5598052385040952),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.655319567155438, 0.7859394650222887),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9185676790251471, 0.9207041942086085),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9731411623179922, 0.9747281215652033),
        )
        private const val SWING_HALF = 10            // +-200 ms of frames
        private const val EPS = 1e-12

        /** Seconds per frame, for counting dropped speech in seconds. */
        const val FRAME_SECONDS = 0.02
    }
}
