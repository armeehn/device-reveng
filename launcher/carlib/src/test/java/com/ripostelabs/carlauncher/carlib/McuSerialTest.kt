package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No byte has been read off `/dev/ttyS1` yet, so nothing here is a capture. The vectors are the
 * vendor writer's algorithm (`SerialPortManager.sendDataEx`) applied to frames that *were* logged,
 * plus one small enough to sum by hand. When a capture exists, it goes at the top of this file.
 */
class McuSerialTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /**
     * Hand-summed: LEN 03 (opcode, one payload byte, CK) + opcode 7E + payload 01 = 0x82,
     * ~0x82 = 0x7D. Opcode 0x7E is the MCU's key-press event in the vendor dispatch.
     */
    private val HAND = bytes(0x0D, 0x0A, 0x03, 0x7E, 0x01, 0x7D, 0x00)

    /** The CAN-box frame logged from `SendCmdLstToCanbus`, as pinned in McuFrameTest. */
    private val INNER_LOGGED = bytes(0x5A, 0xA5, 0x0A, 0xCB,
        0x00, 0x00, 0x0A, 0x00, 0x00, 0x01, 0x18, 0x0C, 0x1B, 0x00, 0x1E)

    /** The real parked-capture 0x32 vehicle-info payload HiworldCanDecoder's self-test uses. */
    private val VEHICLE_INFO = bytes(
        0x00, 0x00, 0x05, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF, 0x00, 0x00, 0x00, 0x00,
    )

    /** `sendDataEx`, transcribed independently of the codec: byte accumulation, then NOT. */
    private fun vendorWrite(body: ByteArray): ByteArray {
        val out = ByteArray(body.size + 5)
        out[0] = 13
        out[1] = 10
        out[2] = ((body.size + 1) and 255).toByte()
        var b = out[2]
        for (i in body.indices) {
            out[i + 3] = body[i]
            b = (b + out[i + 3]).toByte()
        }
        out[body.size + 3] = b.toInt().inv().toByte()
        out[body.size + 4] = 0
        return out
    }

    private fun commands(events: List<McuSerial.Event>) = events.filterIsInstance<McuSerial.Command>()

    @Test
    fun `a hand-summed frame decodes`() {
        val events = McuSerial.Reader().feed(HAND)

        assertEquals(listOf<McuSerial.Event>(McuSerial.Command(0x7E, bytes(0x01))), events)
    }

    @Test
    fun `encode is the vendor writer byte for byte`() {
        assertArrayEquals(HAND, McuSerial.encode(0x7E, bytes(0x01)))

        val body = byteArrayOf(McuSerial.OP_CAN.toByte()) + INNER_LOGGED
        assertArrayEquals(vendorWrite(body), McuSerial.encode(McuSerial.OP_CAN, INNER_LOGGED))
    }

    @Test
    fun `a frame split across reads completes on its last byte`() {
        val reader = McuSerial.Reader()
        val frame = McuSerial.encode(McuSerial.OP_CAN, INNER_LOGGED)

        // Every byte but the checksum yields nothing; the pad is not needed to complete it.
        for (i in 0 until frame.size - 2) {
            assertTrue("byte $i", reader.feed(byteArrayOf(frame[i])).isEmpty())
        }
        val events = reader.feed(byteArrayOf(frame[frame.size - 2]))

        assertEquals(listOf<McuSerial.Event>(McuSerial.Command(McuSerial.OP_CAN, INNER_LOGGED)), events)
        assertTrue(reader.feed(byteArrayOf(frame.last())).isEmpty())
    }

    @Test
    fun `two frames in one read come back in order`() {
        val events = McuSerial.Reader().feed(HAND + McuSerial.encode(0x88, bytes(0x05, 0x06)))

        assertEquals(
            listOf<McuSerial.Event>(
                McuSerial.Command(0x7E, bytes(0x01)),
                McuSerial.Command(0x88, bytes(0x05, 0x06)),
            ),
            events,
        )
    }

    @Test
    fun `the CAN opcode unwraps to the box frame and decodes`() {
        val inner = McuFrame.encode(0x32, VEHICLE_INFO)
        val events = McuSerial.Reader().feed(McuSerial.encode(McuSerial.OP_CAN, inner))

        val frame = commands(events).single().innerFrame()
        assertEquals(McuFrame.Decoded.Frame(0x32, VEHICLE_INFO), frame)

        val signal = HiworldCanDecoder.decodePayload((frame as McuFrame.Decoded.Frame).cmd, frame.payload)
        assertTrue(signal is CanSignal.VehicleInfo)
        assertEquals(1300, (signal as CanSignal.VehicleInfo).rpm)
    }

    @Test
    fun `a pad split off its frame is not junk`() {
        val reader = McuSerial.Reader()
        val frame = McuSerial.encode(0x7E, bytes(0x01))

        reader.feed(frame.copyOf(frame.size - 1))
        val events = reader.feed(byteArrayOf(frame.last()) + HAND)

        assertEquals(listOf<McuSerial.Event>(McuSerial.Command(0x7E, bytes(0x01))), events)
    }

    @Test
    fun `junk before a header is counted, not decoded`() {
        val events = McuSerial.Reader().feed(bytes(0x00, 0xFF, 0x0A) + HAND)

        assertEquals(
            listOf(McuSerial.Skipped(3), McuSerial.Command(0x7E, bytes(0x01))),
            events,
        )
    }

    @Test
    fun `a lone CR at the end of a read waits for its LF`() {
        val reader = McuSerial.Reader()

        assertTrue(reader.feed(bytes(0x0D)).isEmpty())
        val events = reader.feed(HAND.copyOfRange(1, HAND.size))

        assertEquals(listOf<McuSerial.Event>(McuSerial.Command(0x7E, bytes(0x01))), events)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a bad checksum is reported with its body, never as a command`() {
        val bad = HAND.copyOf().also { it[5] = 0x7F }
        val events = McuSerial.Reader().feed(bad + HAND)

        assertEquals(
            listOf(
                McuSerial.BadChecksum(McuSerial.Command(0x7E, bytes(0x01)), expected = 0x7D, actual = 0x7F),
                McuSerial.Command(0x7E, bytes(0x01)),
            ),
            events,
        )
    }

    @Test
    fun `a corrupt LEN cannot swallow the frame behind it`() {
        // LEN says 255; only a real 7-byte frame and silence follow. The wait is bounded by LEN,
        // the rejection lands, and the scan resumes inside the rejected bytes.
        val lying = bytes(0x0D, 0x0A, 0xFF, 0x7E)
        val events = McuSerial.Reader().feed(lying + HAND + ByteArray(300))

        assertEquals(listOf(McuSerial.Command(0x7E, bytes(0x01))), commands(events))
        assertTrue(events.first() is McuSerial.BadChecksum)
    }

    @Test
    fun `a LEN too small for an opcode and a CK is junk wearing a header`() {
        for (len in 0..1) {
            val events = McuSerial.Reader().feed(bytes(0x0D, 0x0A, len, 0x00) + HAND)

            assertEquals("LEN $len", listOf(McuSerial.Command(0x7E, bytes(0x01))), commands(events))
            assertEquals("LEN $len", McuSerial.Skipped(4), events.first())
        }
    }

    @Test
    fun `a non-CAN opcode has no inner frame`() {
        assertNull(McuSerial.Command(0x7E, bytes(0x01)).innerFrame())
    }

    @Test
    fun `a corrupt inner frame is Malformed, not a frame`() {
        // Outer CK is right, so the command arrives; the box frame inside it does not add up.
        val inner = INNER_LOGGED.copyOf().also { it[it.size - 1] = 0x00 }
        val events = McuSerial.Reader().feed(McuSerial.encode(McuSerial.OP_CAN, inner))

        assertTrue(commands(events).single().innerFrame() is McuFrame.Decoded.Malformed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a payload LEN cannot count refuses to encode`() {
        McuSerial.encode(0x7E, ByteArray(McuSerial.MAX_PAYLOAD + 1))
    }
}
