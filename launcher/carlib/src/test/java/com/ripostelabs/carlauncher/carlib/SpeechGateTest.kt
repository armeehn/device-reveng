package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The gate exists for privacy: a window it passes is written to flash and uploaded. So the tests
 * that matter most are the ones where real speech (LibriVox readers) sits inside real car noise
 * (CC0 interior recordings) and must be caught. The noise-only cases pin the other side: a gate
 * that calls everything speech would be private and useless.
 *
 * Fixtures are 16 kHz (see `resources/vad/NOTICE.md`); they are upsampled to the 48 kHz the
 * cabin mic delivers before they reach the gate.
 */
class SpeechGateTest {

    private val gate = SpeechGate()

    // --- noise alone -------------------------------------------------------------------

    @Test
    fun `real forest road interior noise is not speech`() {
        assertFalse(gate.judge(fixture("noise_forest")).speech)
    }

    @Test
    fun `real truck interior noise at speed is not speech`() {
        assertFalse(gate.judge(fixture("noise_truck")).speech)
    }

    @Test
    fun `synthetic rumble, pink noise, engine and fan are not speech`() {
        val noises = listOf("brown" to brown(), "pink" to pink(), "engine" to engine(), "fan" to fan(),
            "hiss" to hiss(), "blower" to blower())
        for ((name, pcm) in noises) {
            val verdict = gate.judge(pcm)
            assertFalse("$name judged speech: $verdict", verdict.speech)
        }
    }

    @Test
    fun `silence is not speech and an empty buffer is safe`() {
        assertFalse(gate.judge(ShortArray(SAMPLE_RATE * 5)).speech)
        assertEquals(0, gate.judge(ShortArray(10)).frames)
    }

    // --- speech inside noise -----------------------------------------------------------

    @Test
    fun `two seconds of speech in road noise is caught at 0 dB`() {
        for (reader in READERS) {
            for (noise in NOISES) {
                val mixed = insert(fixture(noise), fixture(reader), seconds = 2.0, snrDb = 0.0)
                assertTrue("$reader in $noise missed", gate.judge(mixed).speech)
            }
        }
    }

    @Test
    fun `one second of speech in road noise is caught at 0 dB`() {
        for (reader in READERS) {
            for (noise in NOISES) {
                val mixed = insert(fixture(noise), fixture(reader), seconds = 1.0, snrDb = 0.0)
                assertTrue("$reader 1 s in $noise missed", gate.judge(mixed).speech)
            }
        }
    }

    @Test
    fun `speech quieter than the road is still caught at -5 dB`() {
        for (reader in READERS) {
            val mixed = insert(fixture("noise_truck"), fixture(reader), seconds = 2.0, snrDb = -5.0)
            assertTrue("$reader at -5 dB missed", gate.judge(mixed).speech)
        }
    }

    @Test
    fun `speech under blower hiss is caught at 0 dB`() {
        for (reader in READERS) {
            for ((name, noise) in listOf("hiss" to hiss(), "blower" to blower())) {
                val mixed = insert(noise, fixture(reader), seconds = 1.0, snrDb = 0.0)
                assertTrue("$reader under $name missed", gate.judge(mixed).speech)
            }
        }
    }

    @Test
    fun `the verdict is deterministic`() {
        val mixed = insert(fixture("noise_forest"), fixture("speech_01"), seconds = 1.0, snrDb = 0.0)
        assertEquals(gate.judge(mixed), gate.judge(mixed))
    }

    // --- helpers -----------------------------------------------------------------------

    private fun fixture(name: String): ShortArray {
        val bytes = javaClass.getResourceAsStream("/vad/$name.s16")!!.use { it.readBytes() }
        val pcm16 = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm16)
        return upsample(pcm16)
    }

    /** 16 kHz to 48 kHz by linear interpolation: the cabin mic's rate. */
    private fun upsample(pcm16: ShortArray): ShortArray {
        val out = ShortArray(pcm16.size * UPSAMPLE)
        for (i in out.indices) {
            val pos = i.toDouble() / UPSAMPLE
            val a = pcm16[pos.toInt().coerceAtMost(pcm16.size - 1)]
            val b = pcm16[(pos.toInt() + 1).coerceAtMost(pcm16.size - 1)]
            out[i] = (a + (b - a) * (pos - pos.toInt())).toInt().toShort()
        }
        return out
    }

    /** A copy of [noise] with [seconds] of [speech] added at one second in, [snrDb] local SNR. */
    private fun insert(noise: ShortArray, speech: ShortArray, seconds: Double, snrDb: Double): ShortArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val from = (speech.size - n) / 2
        val at = SAMPLE_RATE
        val ps = power(speech, from, n)
        val pn = power(noise, at, n)
        val gain = sqrt(pn * Math.pow(10.0, snrDb / 10) / ps)
        val out = noise.copyOf()
        for (i in 0 until n) {
            val v = out[at + i] + speech[from + i] * gain
            out[at + i] = v.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
        return out
    }

    private fun power(x: ShortArray, from: Int, n: Int): Double {
        var sum = 0.0
        for (i in from until from + n) {
            sum += x[i].toDouble() * x[i]
        }
        return sum / n
    }

    private fun scaled(x: DoubleArray): ShortArray {
        val rms = sqrt(x.sumOf { it * it } / x.size)
        return ShortArray(x.size) { (x[it] / rms * NOISE_RMS).toInt().toShort() }
    }

    private fun brown(): ShortArray {
        val r = Random(1)
        var acc = 0.0
        return scaled(DoubleArray(WINDOW) { acc = acc * 0.999 + r.nextGaussian(); acc })
    }

    /** Paul Kellet's pink filter over white noise. */
    private fun pink(): ShortArray {
        val r = Random(2)
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        return scaled(DoubleArray(WINDOW) {
            val w = r.nextGaussian()
            b0 = 0.99765 * b0 + w * 0.0990460
            b1 = 0.96300 * b1 + w * 0.2965164
            b2 = 0.57000 * b2 + w * 1.0526913
            b0 + b1 + b2 + w * 0.1848
        })
    }

    /** Firing harmonics of a 40 Hz engine that drifts slowly, over rumble. */
    private fun engine(): ShortArray {
        val r = Random(3)
        var phase = 0.0
        var acc = 0.0
        return scaled(DoubleArray(WINDOW) { i ->
            val f0 = 40 + 5 * sin(2 * PI * 0.1 * i / SAMPLE_RATE)
            phase += 2 * PI * f0 / SAMPLE_RATE
            acc = acc * 0.999 + r.nextGaussian()
            (1..11).sumOf { k -> sin(k * phase) / k } + acc / 300
        })
    }

    /** Flat hiss: the hardest case for a pitch detector, every band equally loud. */
    private fun hiss(): ShortArray {
        val r = Random(5)
        return scaled(DoubleArray(WINDOW) { r.nextGaussian() })
    }

    /** A blower as cabins hear it: pink noise and a faint 190 Hz blade tone. */
    private fun blower(): ShortArray {
        val p = pink()
        return ShortArray(WINDOW) { i ->
            (p[i] + 0.3 * NOISE_RMS * sin(2 * PI * 190 * i / SAMPLE_RATE)).toInt().toShort()
        }
    }

    /** Blower hiss with a strong 190 Hz blade tone: periodic, but steady, unlike a voice. */
    private fun fan(): ShortArray {
        val r = Random(4)
        return scaled(DoubleArray(WINDOW) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            0.5 * r.nextGaussian() + (1..4).sumOf { k -> 0.6 / k * sin(2 * PI * 190 * k * t) }
        })
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val UPSAMPLE = 3
        const val WINDOW = SAMPLE_RATE * 20
        const val NOISE_RMS = 1600.0
        val READERS = listOf("speech_01", "speech_03", "speech_10")
        val NOISES = listOf("noise_forest", "noise_truck")
    }
}
