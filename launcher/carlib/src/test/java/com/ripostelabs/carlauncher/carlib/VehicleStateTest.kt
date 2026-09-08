package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The job of this class is to REMEMBER. Each opcode arrives in its own frame, so the failure to
 * guard against is a screen that shows the newest reading and blanks everything else — and the
 * second failure is blanking on a frame we simply could not read.
 */
class VehicleStateTest {

    private val t0 = 1_000L

    @Test
    fun `readings from different frames accumulate`() {
        val state = VehicleState()

        state.onSignal(hybrid(level = 9), t0)
        state.onSignal(info(rpm = 1500, coolant = 74), t0 + 10)

        val snap = state.snapshot.value
        assertEquals(1500, snap.int(VehicleSnapshot.Field.RPM, t0 + 10))
        // The earlier reading survives the later frame.
        assertEquals(9, snap.int(VehicleSnapshot.Field.HYBRID_BATTERY, t0 + 10))
    }

    @Test
    fun `a later reading of the same field replaces the earlier one`() {
        val state = VehicleState()

        state.onSignal(info(rpm = 800, coolant = null), t0)
        state.onSignal(info(rpm = 2400, coolant = null), t0 + 10)

        assertEquals(2400, state.snapshot.value.int(VehicleSnapshot.Field.RPM, t0 + 10))
    }

    @Test
    fun `tiles reflect what has accumulated`() {
        val state = VehicleState()

        state.onSignal(info(rpm = 1500, coolant = 74), t0)

        val labels = state.tiles(t0).map { it.label }
        assertTrue(labels.contains("Engine"))
        assertTrue(labels.contains("Coolant"))
    }

    @Test
    fun `a stale reading stops being shown`() {
        val state = VehicleState()

        state.onSignal(info(rpm = 1500, coolant = null), t0)

        val late = t0 + VehicleSnapshot.STALE_AFTER_MS + 1
        assertNull(state.snapshot.value.int(VehicleSnapshot.Field.RPM, late))
        assertTrue(state.tiles(late).isEmpty())
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an undecodable frame leaves the snapshot untouched`() {
        val state = VehicleState()
        state.onSignal(info(rpm = 1500, coolant = null), t0)

        // Not a valid framed MCU message. A frame we cannot read is not evidence that a good
        // reading has changed, so blanking the dashboard here would be a lie.
        state.onFrame(byteArrayOf(0x00, 0x01, 0x02), t0 + 10)

        assertEquals(1500, state.snapshot.value.int(VehicleSnapshot.Field.RPM, t0 + 10))
    }

    @Test
    fun `a fresh state reports no source and draws nothing`() {
        val state = VehicleState()

        assertFalse(state.hasData(t0))
        assertTrue(state.tiles(t0).isEmpty())
    }

    @Test
    fun `speed is never folded in`() {
        val state = VehicleState()

        state.onSignal(CanSignal.SpeedCandidate(source = "0x17", raw = 880, kmh = 88.0), t0)

        // A real drive proved 0x32 is not road speed. It must not reach a screen as fact.
        assertFalse(state.hasData(t0))
        assertTrue(state.tiles(t0).isEmpty())
    }

    @Test
    fun `one fold mints exactly one calibration sample`() {
        // Two call sites folding the same MCU frame would double every calibration point and make
        // a thin log look twice as convincing. There is one fold, in MainActivity, and this pins
        // that a single call produces a single sample.
        val calibration = SpeedCalibration()
        val state = VehicleState(calibration)

        state.onSignal(CanSignal.TripInfo(rangeToEmptyKm = null, speedCandidateRaw = 280), t0)

        assertEquals(1, calibration.sampleCount())
    }

    private fun info(rpm: Int, coolant: Int?) =
        CanSignal.VehicleInfo(rpm = rpm, speedRaw = 0, speedKmh = 0.0, coolantC = coolant)

    /** Every flow bit false: this test cares about the battery level, not the power path. */
    private fun hybrid(level: Int) = CanSignal.Hybrid(
        present = true,
        batteryLevel = level,
        energyFlowRaw = 0,
        motorDriveBattery = false,
        motorDriveWheels = false,
        engineDriveMotor = false,
        engineDriveWheels = false,
        batteryDriveMotor = false,
        wheelDriveMotor = false,
        batteryDriveWheels = false,
        wheelsDriveBattery = false,
    )
}
