package com.ripostelabs.carlauncher.carlib

/**
 * The noise bands the trainer asks for. The key is the sidecar's `band`, the tags its `tags`.
 */
enum class NoiseBand(val key: String, val tags: List<String>) {
    CITY("city", listOf("City")),
    HIGHWAY("highway", listOf("Highway")),
    CITY_FAN("city-fan", listOf("City", "Fan")),
    HIGHWAY_FAN("highway-fan", listOf("Highway", "Fan")),
}

/**
 * NoisePlan: when may the cabin mic be opened for automatic road-noise capture, and is the window
 * just heard worth keeping?
 *
 *     car state ──hold()──▶ NONE: capture a window      anything else: mic stays closed
 *     window    ──SpeechGate──▶ speech: onSpeech()  (zeroed in memory, hold-off, counted)
 *                             └▶ noise:  onKept()   (written, counts toward drive and band)
 *
 * Refusals, in order of precedence: reverse first (the reverse picture and its chimes own the car),
 * then the owner's switch, ignition, calls, CarPlay, another recorder, being parked, the speech
 * hold-off, the local disk cap, the per-drive budget and the band targets.
 *
 * Budget: at most [DRIVE_CAP_S] of kept noise per drive and [BAND_CAP_S] of it in one band, so a
 * long motorway run does not crowd out the city mix; a band the trainer reports as full
 * (`/v1/wants`) is skipped. Pure and clocked by the caller, so all of it is tested at a desk.
 */
class NoisePlan {

    enum class Switch { ON, OFF }
    enum class Acc { ON, OFF }
    enum class Call { NONE, ACTIVE }
    enum class Session { NONE, ACTIVE }

    enum class Hold {
        NONE, REVERSE, SWITCHED_OFF, ACC_OFF, CALL, CARPLAY, MIC_BUSY, PARKED,
        SPEECH_HOLDOFF, DISK_FULL, DRIVE_DONE, BAND_DONE,
    }

    /** What the car is doing now. [gear] null when the bus has not said. */
    data class Car(
        val acc: Acc,
        val speedKmh: Int,
        val gear: Gear?,
        val call: Call,
        val carPlay: Session,
        val otherRecorders: Int,
        val fanLevel: Int,
        val fanMax: Int,
    )

    /** One band's line from `/v1/wants`: the trainer's target and what the server already has. */
    data class Need(val targetS: Double?, val haveS: Double)

    private val keptByBand = mutableMapOf<NoiseBand, Double>()
    private val pendingByBand = mutableMapOf<NoiseBand, Double>()
    private var wants: Map<String, Need> = emptyMap()
    private var holdUntilMs = 0L

    /** Seconds of kept noise this drive. */
    var keptThisDrive = 0.0
        private set

    /** Seconds of audio thrown away because they held speech, since the launcher started. */
    var droppedSpeechSeconds = 0.0
        private set

    fun band(car: Car): NoiseBand {
        val highway = car.speedKmh >= HIGHWAY_KMH
        val fan = car.fanMax > 0 && car.fanLevel * 2 >= car.fanMax && car.fanLevel > 0
        return when {
            highway && fan -> NoiseBand.HIGHWAY_FAN
            highway -> NoiseBand.HIGHWAY
            fan -> NoiseBand.CITY_FAN
            else -> NoiseBand.CITY
        }
    }

    fun hold(car: Car, switch: Switch, nowMs: Long, queuedBytes: Long): Hold {
        if (car.gear == Gear.REVERSE) {
            return Hold.REVERSE
        }
        if (switch == Switch.OFF) {
            return Hold.SWITCHED_OFF
        }
        if (car.acc == Acc.OFF) {
            return Hold.ACC_OFF
        }
        if (car.call == Call.ACTIVE) {
            return Hold.CALL
        }
        if (car.carPlay == Session.ACTIVE) {
            return Hold.CARPLAY
        }
        if (car.otherRecorders > 0) {
            return Hold.MIC_BUSY
        }
        if (car.speedKmh < MOVING_KMH) {
            return Hold.PARKED
        }
        if (nowMs < holdUntilMs) {
            return Hold.SPEECH_HOLDOFF
        }
        if (queuedBytes + WINDOW_BYTES > DISK_CAP_BYTES) {
            return Hold.DISK_FULL
        }
        if (keptThisDrive + WINDOW_S > DRIVE_CAP_S) {
            return Hold.DRIVE_DONE
        }
        return if (bandFull(band(car))) Hold.BAND_DONE else Hold.NONE
    }

    private fun bandFull(band: NoiseBand): Boolean {
        if ((keptByBand[band] ?: 0.0) + WINDOW_S > BAND_CAP_S) {
            return true
        }
        val need = wants[band.key] ?: return false
        val target = need.targetS ?: return false
        return need.haveS + (pendingByBand[band] ?: 0.0) >= target
    }

    /** A window passed the gate and was written. */
    fun onKept(band: NoiseBand, seconds: Double) {
        keptThisDrive += seconds
        keptByBand[band] = (keptByBand[band] ?: 0.0) + seconds
        pendingByBand[band] = (pendingByBand[band] ?: 0.0) + seconds
    }

    /** A window held speech: it was zeroed, never written. Hold the mic off for a while. */
    fun onSpeech(seconds: Double, nowMs: Long) {
        droppedSpeechSeconds += seconds
        holdUntilMs = nowMs + SPEECH_HOLDOFF_MS
    }

    /** Fresh targets from the server. Its `have_s` now includes what we sent, so pending resets. */
    fun onWants(next: Map<String, Need>) {
        wants = next
        pendingByBand.clear()
    }

    /** ACC came on: a new drive gets a fresh budget. */
    fun newDrive() {
        keptThisDrive = 0.0
        keptByBand.clear()
    }

    companion object {
        /** The owner's "moving" line: below it the cabin is a parked car, not road noise. */
        const val MOVING_KMH = 5
        const val HIGHWAY_KMH = 70
        const val WINDOW_S = 20.0
        const val DRIVE_CAP_S = 180.0
        const val BAND_CAP_S = 60.0
        const val SPEECH_HOLDOFF_MS = 60_000L

        /** 20 s of 48 kHz mono PCM16 plus header. */
        const val WINDOW_BYTES = 20L * 48_000 * 2 + 44

        /** Local queue ceiling: about 50 windows waiting for a network. */
        const val DISK_CAP_BYTES = 100L * 1024 * 1024
    }
}
