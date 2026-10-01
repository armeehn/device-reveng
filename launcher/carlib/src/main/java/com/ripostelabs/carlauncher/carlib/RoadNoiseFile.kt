package com.ripostelabs.carlauncher.carlib

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * RoadNoiseFile: one kept window on flash, in the format the trainer reads (the server's
 * road-noise contract, schema `road-noise/1`): a 48 kHz mono PCM16 WAV, and a JSON sidecar
 * describing the car while it was recorded.
 */
object RoadNoiseFile {

    const val SAMPLE_RATE = 48_000
    const val SCHEMA = "road-noise/1"
    const val SOURCE_AUTO = "auto"
    const val MIC_SOURCE = "VOICE_COMMUNICATION"
    const val SILENT_PEAK = 8
    private const val HEADER_BYTES = 44
    private const val BYTES_PER_SAMPLE = 2
    private const val PCM = 1
    private const val MONO = 1
    private const val BITS = 16

    /**
     * A window whose loudest sample stays under [SILENT_PEAK] (about -72 dBFS) is a microphone
     * that delivered nothing (muted, unplugged, an emulator): no road is that quiet, and the
     * trainer must not learn silence as noise.
     */
    fun isSilent(pcm: ShortArray, length: Int = pcm.size): Boolean {
        for (i in 0 until length) {
            if (kotlin.math.abs(pcm[i].toInt()) >= SILENT_PEAK) {
                return false
            }
        }
        return true
    }

    fun writeWav(file: File, pcm: ShortArray, length: Int) {
        val data = length * BYTES_PER_SAMPLE
        val buf = ByteBuffer.allocate(HEADER_BYTES + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(HEADER_BYTES - 8 + data).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(PCM.toShort()).putShort(MONO.toShort())
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
            .putShort(BYTES_PER_SAMPLE.toShort()).putShort(BITS.toShort())
        buf.put("data".toByteArray()).putInt(data)
        for (i in 0 until length) {
            buf.putShort(pcm[i])
        }
        file.writeBytes(buf.array())
    }

    fun sidecar(
        startedAt: Instant,
        seconds: Double,
        band: NoiseBand,
        speedKmh: Double,
        gear: Gear?,
        fanLevel: Int,
        ac: Boolean?,
        doorsOpen: Boolean?,
        mediaActive: Boolean,
        verdict: SpeechGate.Verdict,
        appVersion: String,
    ): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("started_at", DateTimeFormatter.ISO_INSTANT.format(startedAt))
        .put("duration_s", seconds)
        .put("sample_rate", SAMPLE_RATE)
        .put("channels", MONO)
        .put("source", SOURCE_AUTO)
        .put("band", band.key)
        .put("tags", JSONArray(band.tags))
        .put("speed_kmh", speedKmh)
        .put("gear", gearLetter(gear) ?: JSONObject.NULL)
        .put("hvac", JSONObject().put("fan_level", fanLevel).put("ac", ac ?: JSONObject.NULL))
        .put("doors_open", doorsOpen ?: JSONObject.NULL)
        .put("media_active", mediaActive)
        .put("mic_source", MIC_SOURCE)
        .put("vad", JSONObject().put("method", SpeechGate.METHOD)
            .put("speech_frames", verdict.speechFrames).put("frames", verdict.frames))
        .put("app_version", appVersion)

    private fun gearLetter(gear: Gear?): String? = when (gear) {
        Gear.PARK -> "P"
        Gear.REVERSE -> "R"
        Gear.NEUTRAL -> "N"
        Gear.DRIVE -> "D"
        Gear.UNKNOWN, null -> null
    }
}
