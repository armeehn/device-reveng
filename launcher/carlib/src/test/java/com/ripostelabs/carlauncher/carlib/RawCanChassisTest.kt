package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.RawCanSignal.GearPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chassis signals pinned to frames copied verbatim from the 2026-09-09 drive log. Each expected
 * value is what opendbc's field definition yields for that frame, and each field earned its place
 * by agreeing with physics on the drive (see RawCanDecoder), not by its name.
 */
class RawCanChassisTest {

    private fun frame(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private inline fun <reified T : RawCanSignal> decode(id: Int, vararg b: Int): T = RawCanDecoder.decode(id, frame(*b)) as T

    /** 0x0F69: 12-bit two's complement −151 → −226.5°. 0x0FFE → −3.0°, straight-ish at 31 km/h. */
    @Test
    fun `steering angle is a signed 12-bit field at 1,5 degrees per lsb`() {
        assertEquals(-226.5, decode<RawCanSignal.SteeringAngle>(RawCanDecoder.ID_STEERING, 0x0F, 0x69, 0x00, 0x02, 0xAF, 0x5F, 0x00, 0xB5).degrees, 0.001)
        assertEquals(-3.0, decode<RawCanSignal.SteeringAngle>(RawCanDecoder.ID_STEERING, 0x0F, 0xFE, 0x00, 0x02, 0xA0, 0x13, 0x00, 0xEF).degrees, 0.001)
    }

    /** Yaw 0x1D7 = 471 → −10.08°/s while turning; lateral 0x231 = 561 → 1.76 m/s². */
    @Test
    fun `inertial frame yields yaw rate and lateral g`() {
        val i = decode<RawCanSignal.Inertial>(RawCanDecoder.ID_INERTIAL, 0x01, 0xD7, 0x02, 0x31, 0x41, 0xED, 0x80, 0xE5)
        assertEquals(-10.076, i.yawDegS, 0.001)
        assertEquals(1.759, i.lateralMs2, 0.001)
    }

    /** Byte 4 = 0x1F → 1.24 m/s² pulling away from a stop. */
    @Test
    fun `longitudinal acceleration is a signed byte at 0,04`() {
        assertEquals(1.24, decode<RawCanSignal.LongAccel>(RawCanDecoder.ID_LONG_ACCEL, 0, 0, 0, 0, 0x1F, 0x01, 0x00, 0x4B).ms2, 0.001)
        assertEquals(-0.40, decode<RawCanSignal.LongAccel>(RawCanDecoder.ID_LONG_ACCEL, 0, 0, 0, 0, 0xF6, 0x01, 0x00, 0x4B).ms2, 0.001)
    }

    /** 0x0015 = 21 → 0.42 MPa braking from 49 km/h; 0 with the pedal up at 31 km/h. */
    @Test
    fun `brake pressure is zero exactly when the pedal is up`() {
        val braking = decode<RawCanSignal.Brake>(RawCanDecoder.ID_BRAKE, 0x00, 0x15, 0x10, 0x20, 0x20, 0x00, 0x00, 0x00)
        assertEquals(0.42, braking.pressureMpa, 0.001)
        assertTrue(braking.pressed)

        val up = decode<RawCanSignal.Brake>(RawCanDecoder.ID_BRAKE, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(0.0, up.pressureMpa, 0.0)
        assertFalse(up.pressed)
    }

    /** 0x0514 = 1300 × 0.78125 = 1015 rpm, intake 0x1A → 25°C; the hybrid at 0 rpm doing 59 km/h. */
    @Test
    fun `engine frame yields rpm and intake temperature`() {
        val running = decode<RawCanSignal.Engine>(RawCanDecoder.ID_ENGINE, 0x05, 0x14, 0x1A, 0x08, 0x50, 0x00, 0x3E, 0x96)
        assertEquals(1015, running.rpm)
        assertEquals(25.0, running.intakeC, 0.001)

        assertEquals(0, decode<RawCanSignal.Engine>(RawCanDecoder.ID_ENGINE, 0x00, 0x00, 0x1A, 0x00, 0x50, 0x00, 0x3E, 0x75).rpm)
    }

    /** The three payloads the drive produced: D the whole way, then R and P while parking. */
    @Test
    fun `gear flags decode to D R and P`() {
        assertEquals(GearPos.D, decode<RawCanSignal.Gear>(RawCanDecoder.ID_GEAR, 0x00, 0x00, 0x00, 0x00, 0x00, 0x81, 0x00, 0x30).position)
        assertEquals(GearPos.R, decode<RawCanSignal.Gear>(RawCanDecoder.ID_GEAR, 0x00, 0x10, 0x00, 0x00, 0x00, 0x01, 0x00, 0x30).position)
        assertEquals(GearPos.P, decode<RawCanSignal.Gear>(RawCanDecoder.ID_GEAR, 0x00, 0x20, 0x00, 0x00, 0x00, 0x01, 0x00, 0x30).position)
        assertEquals(GearPos.UNKNOWN, decode<RawCanSignal.Gear>(RawCanDecoder.ID_GEAR, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x30).position)
    }

    /** 0x01C1EC = 115 180 km at the start of the drive. */
    @Test
    fun `odometer is 24 bits of whole km`() {
        assertEquals(115180, decode<RawCanSignal.Odometer>(RawCanDecoder.ID_ODOMETER, 0x21, 0x00, 0x00, 0x10, 0x00, 0x01, 0xC1, 0xEC).km)
    }

    /** End to end: gear, odometer and a pressed brake reach the tiles; an idle brake does not. */
    @Test
    fun `chassis signals reach the vehicle tiles`() {
        val state = VehicleState()
        val t = 5_000L
        state.onRawFrame(RawCanDecoder.ID_GEAR, frame(0x00, 0x00, 0x00, 0x00, 0x00, 0x81, 0x00, 0x30), t)
        state.onRawFrame(RawCanDecoder.ID_ODOMETER, frame(0x21, 0x00, 0x00, 0x10, 0x00, 0x01, 0xC1, 0xEC), t)
        state.onRawFrame(RawCanDecoder.ID_BRAKE, frame(0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00), t)
        val tiles = state.tiles(now = t).associate { it.label to it.value }

        assertEquals("D", tiles["Gear"])
        assertEquals("115180 km", tiles["Odometer"])
        assertFalse(tiles.containsKey("Brake"))
    }
}
