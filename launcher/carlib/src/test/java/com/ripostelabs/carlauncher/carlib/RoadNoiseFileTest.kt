package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/**
 * The trainer and the ingest server read these files without the car to ask: the WAV must be
 * exactly 48 kHz mono PCM16 and the sidecar must carry every key the contract requires.
 */
class RoadNoiseFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the WAV header says 48 kHz mono PCM16 and the data follows`() {
        val pcm = ShortArray(4800) { (it % 100).toShort() }
        val f = tmp.root.resolve("w.wav")
        RoadNoiseFile.writeWav(f, pcm, 4000)

        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 8000, f.length().toInt())
        assertEquals("RIFF", String(f.readBytes(), 0, 4))
        assertEquals("WAVE", String(f.readBytes(), 8, 4))
        assertEquals(1, b.getShort(20).toInt())        // PCM
        assertEquals(1, b.getShort(22).toInt())        // mono
        assertEquals(48_000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(8000, b.getInt(40))
        assertEquals(99, b.getShort(44 + 2 * 99).toInt())
    }

    @Test
    fun `the sidecar carries every required contract key`() {
        val meta = RoadNoiseFile.sidecar(
            startedAt = Instant.parse("2026-10-01T16:02:11Z"), seconds = 20.0, band = NoiseBand.HIGHWAY_FAN,
            speedKmh = 104.5, gear = Gear.DRIVE, fanLevel = 5, ac = true, doorsOpen = false,
            mediaActive = false, verdict = SpeechGate.Verdict(1000, 2, 1, false), appVersion = "0.7 (999)",
        )
        for (key in listOf("schema", "started_at", "duration_s", "sample_rate", "channels", "source")) {
            assertTrue("missing $key", meta.has(key))
        }
        assertEquals("road-noise/1", meta.getString("schema"))
        assertEquals("2026-10-01T16:02:11Z", meta.getString("started_at"))
        assertEquals("auto", meta.getString("source"))
        assertEquals("highway-fan", meta.getString("band"))
        assertEquals("Fan", meta.getJSONArray("tags").getString(1))
        assertEquals("D", meta.getString("gear"))
        assertEquals(5, meta.getJSONObject("hvac").getInt("fan_level"))
        assertEquals("riposte-vad/1", meta.getJSONObject("vad").getString("method"))
        assertEquals("VOICE_COMMUNICATION", meta.getString("mic_source"))
    }

    @Test
    fun `a dead microphone is silent, any road is not`() {
        assertTrue(RoadNoiseFile.isSilent(ShortArray(48_000)))
        assertTrue(RoadNoiseFile.isSilent(ShortArray(48_000) { (it % 7 - 3).toShort() }))
        val road = ShortArray(48_000)
        road[30_000] = 40
        assertEquals(false, RoadNoiseFile.isSilent(road))
    }
}
