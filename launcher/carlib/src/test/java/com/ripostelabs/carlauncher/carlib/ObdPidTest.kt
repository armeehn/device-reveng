package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The standard parameters, their J1979 formulas, and the rule that the launcher never transmits
 * more often because it asks about more things.
 *
 * These values are the one kind in this project that needs no actuation to trust: the ECU answers
 * a published standard. The tests therefore pin the decode against the standard's own arithmetic,
 * and spend most of their effort on the strictness that stops one parameter being read as another.
 */
class ObdPidTest {

    private fun ecu(id: Int, vararg bytes: Int) = SlcanFrame(id, bytes.toMutableList())

    private fun value(vararg bytes: Int): Obd.Reply.Value? =
        Obd.parse(ecu(0x7E8, *bytes)) as? Obd.Reply.Value

    /** J1979: coolant is the raw byte less 40, so 0x7B is 83 C and 0x00 is -40 C. */
    @Test
    fun `coolant carries the standard forty degree offset`() {
        assertEquals(83.0, value(0x03, 0x41, 0x05, 0x7B)!!.value, 0.001)
        assertEquals(-40.0, value(0x03, 0x41, 0x05, 0x00)!!.value, 0.001)
        assertEquals(215.0, value(0x03, 0x41, 0x05, 0xFF)!!.value, 0.001)
    }

    /** J1979: load and throttle scale a full byte to 100%. */
    @Test
    fun `load and throttle scale a byte to a percentage`() {
        assertEquals(100.0, value(0x03, 0x41, 0x04, 0xFF)!!.value, 0.001)
        assertEquals(0.0, value(0x03, 0x41, 0x04, 0x00)!!.value, 0.001)
        assertEquals(50.196, value(0x03, 0x41, 0x11, 0x80)!!.value, 0.001)
    }

    @Test
    fun `speed is the raw byte in kmh`() {
        assertEquals(54.0, value(0x03, 0x41, 0x0D, 0x36)!!.value, 0.001)
    }

    @Test
    fun `each parameter decodes as itself`() {
        assertEquals(ObdPid.COOLANT_C, value(0x03, 0x41, 0x05, 0x7B)!!.pid)
        assertEquals(ObdPid.ENGINE_LOAD, value(0x03, 0x41, 0x04, 0x7B)!!.pid)
        assertEquals(ObdPid.THROTTLE_PCT, value(0x03, 0x41, 0x11, 0x7B)!!.pid)
        assertEquals(ObdPid.SPEED_KMH, value(0x03, 0x41, 0x0D, 0x7B)!!.pid)
    }

    /**
     * The same payload byte must mean different things under parameters with different formulas.
     * This is what would catch a decode wired to the wrong parameter.
     *
     * Load and throttle are deliberately excluded from the comparison: J1979 gives them the same
     * byte-to-percent scaling, so they SHOULD agree on equal input, and asserting otherwise would
     * be asserting a bug. They are told apart by their PID, which the test below pins.
     */
    @Test
    fun `one payload byte means something different per formula`() {
        val raw = 0x7B
        val readings = listOf(0x05, 0x04, 0x0D).map { value(0x03, 0x41, it, raw)!!.value }

        assertEquals(readings.size, readings.toSet().size)
    }

    /** Same scale, different parameter: the reading is shared, the identity is not. */
    @Test
    fun `load and throttle share a scale but not an identity`() {
        val load = value(0x03, 0x41, 0x04, 0x7B)!!
        val throttle = value(0x03, 0x41, 0x11, 0x7B)!!

        assertEquals(load.value, throttle.value, 0.001)
        assertTrue(load.pid != throttle.pid)
    }

    /** A parameter this car does not support must not be invented from a stray reply. */
    @Test
    fun `an unknown pid is not decoded`() {
        assertNull(Obd.parse(ecu(0x7E8, 0x03, 0x41, 0x2F, 0x40)))
    }

    /**
     * Length is part of identity. A reply claiming one data byte for a parameter that carries two
     * — or the reverse — is malformed, and reading it anyway would invent a number.
     */
    @Test
    fun `a length that contradicts the parameter is refused`() {
        assertNotNull(Obd.parse(ecu(0x7E8, 0x03, 0x41, 0x05, 0x7B)))
        assertNull(Obd.parse(ecu(0x7E8, 0x04, 0x41, 0x05, 0x7B, 0x00)))
        assertNull(Obd.parse(ecu(0x7E8, 0x02, 0x41, 0x05, 0x7B)))
    }

    @Test
    fun `a request names its own parameter and is padded`() {
        val frame = Obd.request(ObdPid.COOLANT_C)

        assertEquals(Obd.REQUEST_ID, frame.id)
        assertEquals(8, frame.data.size)
        assertEquals(ObdPid.COOLANT_C.code, frame.data[2])
    }

    /**
     * The bus-budget rule: asking about more parameters must not mean transmitting more often.
     * Over a full cycle every parameter is asked exactly once, and the poller is what sets rate.
     */
    @Test
    fun `the rotation asks every parameter once per cycle`() {
        val rotation = ObdRotation()
        val cycle = ObdPid.entries.map { rotation.next() }

        assertEquals(ObdPid.entries.toSet(), cycle.toSet())
        assertEquals(ObdPid.entries.size, cycle.size)
    }

    @Test
    fun `the rotation wraps rather than running out`() {
        val rotation = ObdRotation()
        val twoCycles = (1..ObdPid.entries.size * 2).map { rotation.next() }

        assertEquals(twoCycles.take(ObdPid.entries.size), twoCycles.drop(ObdPid.entries.size))
    }

    /** The rate is the poller's alone, and it does not know how many parameters there are. */
    @Test
    fun `the send rate is unaffected by the number of parameters`() {
        val poller = ObdPoller(intervalMs = 500L)

        assertTrue(poller.shouldSend(now = 1_000L))
        assertTrue(!poller.shouldSend(now = 1_400L))
        assertTrue(poller.shouldSend(now = 1_500L))
    }

    /**
     * Speed must NOT reach the snapshot. The raw bus owns that field at 15-39 Hz and the safety
     * gate reads it; this reply arrives every couple of seconds as a cross-check, and letting
     * both write one field would make the gate alternate between sources of different ages.
     */
    @Test
    fun `an OBD speed reply never reaches the vehicle tiles`() {
        val state = VehicleState()
        val t = 1_000L

        state.onObd(Obd.Reply.Value(ObdPid.SPEED_KMH, 54.0, ecu = 0x7E8), t)

        assertTrue(state.tiles(now = t).none { it.label == "Speed" })
        assertNull(state.snapshot.value.speedKmh)
    }

    /** A refusal is not a reading of NRC units, and must leave the snapshot untouched. */
    @Test
    fun `a refusal changes nothing`() {
        val state = VehicleState()
        val t = 1_000L

        state.onObd(Obd.Reply.Refused(nrc = 0x12, ecu = 0x7E8), t)

        assertTrue(state.tiles(now = t).isEmpty())
    }

    @Test
    fun `coolant load and throttle reach the tiles`() {
        val state = VehicleState()
        val t = 1_000L

        state.onObd(Obd.Reply.Value(ObdPid.COOLANT_C, 83.0, ecu = 0x7E8), t)
        state.onObd(Obd.Reply.Value(ObdPid.ENGINE_LOAD, 42.4, ecu = 0x7E8), t)
        state.onObd(Obd.Reply.Value(ObdPid.THROTTLE_PCT, 12.6, ecu = 0x7E8), t)
        val tiles = state.tiles(now = t).associate { it.label to it.value }

        assertEquals("83\u00B0C", tiles["Coolant"])
        assertEquals("42%", tiles["Engine load"])
        assertEquals("13%", tiles["Throttle"])
    }

    /**
     * The ECU outranks the vendor digest for coolant. The one coolant reading this project ever
     * checked against a standard PID was wrong by 14 degrees and moving the wrong way.
     */
    @Test
    fun `the ECU coolant wins over the vendor digest`() {
        val state = VehicleState()
        val t = 1_000L

        state.onSignal(CanSignal.VehicleInfo(rpm = 800, speedRaw = 0, speedKmh = 0.0, coolantC = 60), t)
        assertEquals("60\u00B0C", state.tiles(now = t).first { it.label == "Coolant" }.value)

        state.onObd(Obd.Reply.Value(ObdPid.COOLANT_C, 83.0, ecu = 0x7E8), t)
        assertEquals("83\u00B0C", state.tiles(now = t).first { it.label == "Coolant" }.value)
    }
}
