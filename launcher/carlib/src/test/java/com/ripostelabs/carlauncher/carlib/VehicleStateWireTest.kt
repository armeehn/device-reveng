package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * From the bytes on the wire to the words on the tile, with nothing skipped.
 *
 * Every earlier test on this path starts from a decoded signal. This one starts from the slcan
 * line the adapter actually sends, so the last untested link — the signed-byte round trip from
 * [SlcanFrame] into the decoder — is covered. A raw door byte is 0x80, which is exactly the value
 * a careless conversion turns into -128 and a decoder then fails to match.
 */
class VehicleStateWireTest {

    private val t0 = 1_000L

    private fun frame(line: String): SlcanFrame =
        (SlcanCodec.decode(line) as SlcanEvent.Received).frame

    private fun openings(state: VehicleState): String? =
        state.tiles(t0).firstOrNull { it.label == "Open" }?.value

    @Test
    fun `a raw driver-door frame lights the driver tile`() {
        val state = VehicleState()

        // 0x4A5, byte 3 = 0x80: driver on the RAW layout, as captured by actuation on this car.
        state.onRawFrame(frame("t4A580000008000000000"), t0)

        assertEquals("driver", openings(state))
    }

    @Test
    fun `the high bit survives the trip through a signed byte`() {
        val state = VehicleState()

        state.onRawFrame(frame("t4A58000000FF00000000"), t0)

        // Every opening set: if 0x80 had become -128 the driver would be missing from this list.
        assertEquals("driver, passenger, rear left, rear right, tailgate, bonnet", openings(state))
    }

    @Test
    fun `a raw fan frame reaches the climate tile`() {
        val state = VehicleState()

        state.onRawFrame(frame("t4AD80000000000005500"), t0)

        val snap = state.snapshot.value
        assertEquals(0x55 - 0x52, snap.int(VehicleSnapshot.Field.FAN_STEP, t0))
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the raw passenger bit is not the driver`() {
        val state = VehicleState()

        // 0x40 is the PASSENGER on the raw layout and the DRIVER on the MCU layout. Reading raw
        // bytes with MCU constants — the original bug — would make this say "driver".
        state.onRawFrame(frame("t4A580000004000000000"), t0)

        assertEquals("passenger", openings(state))
    }

    @Test
    fun `an id nobody decodes changes nothing`() {
        val state = VehicleState()
        state.onRawFrame(frame("t4A580000008000000000"), t0)

        // 0x1C4 is on the bus and once looked like coolant. It is not decoded, so it must not
        // disturb what is already known.
        state.onRawFrame(frame("t1C48FFFFFFFFFFFFFFFF"), t0 + 1)

        assertEquals("driver", openings(state))
    }

    @Test
    fun `a short frame is dropped, not misread`() {
        val state = VehicleState()

        // DLC 2: the door byte does not exist, and indexing it would be a guess.
        state.onRawFrame(frame("t4A520080"), t0)

        assertNull(openings(state))
        assertTrue(state.tiles(t0).isEmpty())
    }
}
