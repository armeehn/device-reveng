package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The plan decides when the cabin mic may be opened at all. Every refusal reason has its own
 * test, because each one guards something different: the driver's call, the reverse picture's
 * audio, another app's recording, the owner's switch, the flash, and the trainer's appetite.
 */
class NoisePlanTest {

    private val cruising = NoisePlan.Car(
        acc = NoisePlan.Acc.ON, speedKmh = 50, gear = Gear.DRIVE, call = NoisePlan.Call.NONE,
        carPlay = NoisePlan.Session.NONE, otherRecorders = 0, fanLevel = 1, fanMax = 7,
    )

    private fun plan() = NoisePlan()

    @Test
    fun `cruising with the switch on captures`() {
        assertEquals(NoisePlan.Hold.NONE, plan().hold(cruising, NoisePlan.Switch.ON, nowMs = 0, queuedBytes = 0))
    }

    @Test
    fun `every reason to stay silent is honoured`() {
        val p = plan()
        val cases = listOf(
            cruising.copy(acc = NoisePlan.Acc.OFF) to NoisePlan.Hold.ACC_OFF,
            cruising.copy(speedKmh = 4) to NoisePlan.Hold.PARKED,
            cruising.copy(speedKmh = GpsSpeedSource.SPEED_UNKNOWN) to NoisePlan.Hold.PARKED,
            cruising.copy(gear = Gear.REVERSE) to NoisePlan.Hold.REVERSE,
            cruising.copy(call = NoisePlan.Call.ACTIVE) to NoisePlan.Hold.CALL,
            cruising.copy(carPlay = NoisePlan.Session.ACTIVE) to NoisePlan.Hold.CARPLAY,
            cruising.copy(otherRecorders = 1) to NoisePlan.Hold.MIC_BUSY,
        )
        for ((car, want) in cases) {
            assertEquals("$car", want, p.hold(car, NoisePlan.Switch.ON, nowMs = 0, queuedBytes = 0))
        }
        assertEquals(NoisePlan.Hold.SWITCHED_OFF, p.hold(cruising, NoisePlan.Switch.OFF, 0, 0))
    }

    @Test
    fun `reverse wins over everything else`() {
        val car = cruising.copy(gear = Gear.REVERSE, call = NoisePlan.Call.ACTIVE, speedKmh = 3)
        assertEquals(NoisePlan.Hold.REVERSE, plan().hold(car, NoisePlan.Switch.ON, 0, 0))
    }

    @Test
    fun `bands follow speed and fan`() {
        val p = plan()
        assertEquals(NoiseBand.CITY, p.band(cruising))
        assertEquals(NoiseBand.HIGHWAY, p.band(cruising.copy(speedKmh = 100)))
        assertEquals(NoiseBand.CITY_FAN, p.band(cruising.copy(fanLevel = 4)))
        assertEquals(NoiseBand.HIGHWAY_FAN, p.band(cruising.copy(speedKmh = 110, fanLevel = 7)))
        assertEquals(listOf("Highway", "Fan"), NoiseBand.HIGHWAY_FAN.tags)
    }

    @Test
    fun `a drive stops after three minutes of kept noise`() {
        val p = plan()
        repeat(9) { p.onKept(NoiseBand.values()[it % 4], seconds = 20.0) }
        assertEquals(NoisePlan.Hold.DRIVE_DONE, p.hold(cruising, NoisePlan.Switch.ON, 0, 0))

        // ACC off and on again is a new drive.
        p.newDrive()
        assertEquals(NoisePlan.Hold.NONE, p.hold(cruising, NoisePlan.Switch.ON, 0, 0))
    }

    @Test
    fun `one band gets at most a minute per drive, so the mix spreads`() {
        val p = plan()
        repeat(3) { p.onKept(NoiseBand.CITY, seconds = 20.0) }
        assertEquals(NoisePlan.Hold.BAND_DONE, p.hold(cruising, NoisePlan.Switch.ON, 0, 0))
        assertEquals(NoisePlan.Hold.NONE, p.hold(cruising.copy(speedKmh = 100), NoisePlan.Switch.ON, 0, 0))
    }

    @Test
    fun `a band the trainer has enough of is skipped`() {
        val p = plan()
        p.onWants(mapOf("city" to NoisePlan.Need(targetS = 600.0, haveS = 600.0),
                        "highway" to NoisePlan.Need(targetS = 600.0, haveS = 10.0)))
        assertEquals(NoisePlan.Hold.BAND_DONE, p.hold(cruising, NoisePlan.Switch.ON, 0, 0))
        assertEquals(NoisePlan.Hold.NONE, p.hold(cruising.copy(speedKmh = 100), NoisePlan.Switch.ON, 0, 0))
    }

    @Test
    fun `local captures not yet counted by the server still count toward the target`() {
        val p = plan()
        p.onWants(mapOf("highway" to NoisePlan.Need(targetS = 40.0, haveS = 0.0)))
        val highway = cruising.copy(speedKmh = 100)
        p.onKept(NoiseBand.HIGHWAY, seconds = 20.0)
        assertEquals(NoisePlan.Hold.NONE, p.hold(highway, NoisePlan.Switch.ON, 0, 0))
        p.onKept(NoiseBand.HIGHWAY, seconds = 20.0)
        assertEquals(NoisePlan.Hold.BAND_DONE, p.hold(highway, NoisePlan.Switch.ON, 0, 0))
    }

    @Test
    fun `speech holds the mic off for a minute and is counted, never kept`() {
        val p = plan()
        p.onSpeech(seconds = 20.0, nowMs = 1_000)
        assertEquals(NoisePlan.Hold.SPEECH_HOLDOFF, p.hold(cruising, NoisePlan.Switch.ON, nowMs = 30_000, queuedBytes = 0))
        assertEquals(NoisePlan.Hold.NONE, p.hold(cruising, NoisePlan.Switch.ON, nowMs = 61_001, queuedBytes = 0))
        assertEquals(20.0, p.droppedSpeechSeconds, 0.0)
        assertEquals(0.0, p.keptThisDrive, 0.0)
    }

    @Test
    fun `a full local queue stops capture`() {
        val p = plan()
        val full = NoisePlan.DISK_CAP_BYTES
        assertEquals(NoisePlan.Hold.DISK_FULL, p.hold(cruising, NoisePlan.Switch.ON, 0, queuedBytes = full))
    }
}
