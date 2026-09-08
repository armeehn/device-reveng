package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format is the only thing standing between a bulk endpoint and a decoded car signal, so
 * it is tested for what it must REFUSE as much as for what it parses. Every "not a frame" case
 * below is a negative control: a codec that accepted them would invent CAN traffic out of noise.
 */
class SlcanCodecTest {

    @Test
    fun `startup closes then sets bitrate then opens`() {
        val commands = SlcanCodec.startup(SlcanBitrate.KBIT_500).map { String(it) }

        assertEquals(listOf("C\r", "S6\r", "O\r"), commands)
    }

    @Test
    fun `transmit matches the OBD request the shell probe sends`() {
        // Verbatim from headunit-drive-capture.sh: mode 01 PID 0D, vehicle speed.
        val frame = SlcanFrame(0x7DF, listOf(0x02, 0x01, 0x0D, 0, 0, 0, 0, 0))

        assertEquals("t7DF802010D0000000000\r", String(SlcanCodec.transmit(frame)))
    }

    @Test
    fun `transmit pads an extended id to eight digits`() {
        val frame = SlcanFrame(0x18DAF110, listOf(0xFF), IdFormat.EXTENDED)

        assertEquals("T18DAF1101FF\r", String(SlcanCodec.transmit(frame)))
    }

    @Test
    fun `decode reads a standard frame`() {
        val event = SlcanCodec.decode("t4A580400000080000000")

        val frame = (event as SlcanEvent.Received).frame
        assertEquals(0x4A5, frame.id)
        assertEquals(listOf(0x04, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00), frame.data)
        assertEquals(IdFormat.STANDARD, frame.format)
    }

    @Test
    fun `decode reads an extended frame`() {
        val event = SlcanCodec.decode("T18FF00212AABB")

        val frame = (event as SlcanEvent.Received).frame
        assertEquals(0x18FF0021, frame.id)
        assertEquals(listOf(0xAA, 0xBB), frame.data)
        assertEquals(IdFormat.EXTENDED, frame.format)
    }

    @Test
    fun `decode reads a zero-length frame`() {
        val frame = (SlcanCodec.decode("t1230") as SlcanEvent.Received).frame

        assertEquals(0x123, frame.id)
        assertTrue(frame.data.isEmpty())
    }

    @Test
    fun `empty line is the adapter acknowledging a command`() {
        assertEquals(SlcanEvent.Ack, SlcanCodec.decode(""))
    }

    @Test
    fun `render round-trips an id and payload`() {
        assertEquals("4A5#04 00 80", SlcanFrame(0x4A5, listOf(4, 0, 0x80)).render())
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a truncated payload is not a short frame`() {
        // DLC says 8 bytes, only 3 arrived. Accepting this would fabricate a frame of zeros.
        assertTrue(SlcanCodec.decode("t7DF8020" + "10D") is SlcanEvent.Text)
    }

    @Test
    fun `trailing junk after a complete payload is rejected`() {
        assertTrue(SlcanCodec.decode("t1231AA99") is SlcanEvent.Text)
    }

    @Test
    fun `a dlc above eight is rejected`() {
        assertTrue(SlcanCodec.decode("t123900000000000000000") is SlcanEvent.Text)
    }

    @Test
    fun `non-hex in the id is rejected`() {
        assertTrue(SlcanCodec.decode("t1Z31AA") is SlcanEvent.Text)
    }

    @Test
    fun `a signed hex string is rejected`() {
        // toIntOrNull(16) would happily read "-12" as -18 and yield a negative CAN id.
        assertTrue(SlcanCodec.decode("t-121AA") is SlcanEvent.Text)
    }

    @Test
    fun `an unknown prefix is passed through as text`() {
        val event = SlcanCodec.decode("V1013")

        assertEquals("V1013", (event as SlcanEvent.Text).text)
    }

    // ── Reassembly across bulk-read boundaries ──────────────────────────────────────────────────

    @Test
    fun `a frame split across two reads decodes once`() {
        val reader = SlcanReader()

        assertTrue(reader.feed("t4A5804000000".toByteArray()).isEmpty())
        val events = reader.feed("80000000\r".toByteArray())

        assertEquals(0x4A5, (events.single() as SlcanEvent.Received).frame.id)
    }

    @Test
    fun `several frames in one read all decode`() {
        val reader = SlcanReader()

        val events = reader.feed("t1231AA\rt4562BBCC\r".toByteArray())

        assertEquals(2, events.size)
        assertEquals(0x123, (events[0] as SlcanEvent.Received).frame.id)
        assertEquals(0x456, (events[1] as SlcanEvent.Received).frame.id)
    }

    @Test
    fun `bell reports a rejected command and drops the partial line`() {
        val reader = SlcanReader()

        val events = reader.feed("S9\u0007t1231AA\r".toByteArray())

        assertEquals(SlcanEvent.Rejected, events[0])
        assertEquals(0x123, (events[1] as SlcanEvent.Received).frame.id)
    }

    @Test
    fun `an unterminated flood does not grow without bound`() {
        val reader = SlcanReader()

        // 4 kB of garbage with no CR: must yield nothing, and must NOT splice its tail onto
        // the next frame. Resync happens at the following delimiter, not mid-line.
        assertTrue(reader.feed("x".repeat(4096).toByteArray()).isEmpty())
        val events = reader.feed("\rt1231AA\r".toByteArray())

        // The first delimiter closes the dropped line, the frame after it decodes normally.
        assertTrue((events.first() as SlcanEvent.Text).text.contains("overlong"))
        assertEquals(0x123, (events.last() as SlcanEvent.Received).frame.id)
    }

    // ── What the adapter says when it is not answering a command ────────────────────────────────

    @Test
    fun `the connect banner survives and does not eat the next frame`() {
        val reader = SlcanReader()

        // Verbatim from the head unit, 2026-09-08: 51 characters, CRLF-terminated.
        val banner = "16e7497-dirty github.com/normaldotcom/canable2.git"
        val events = reader.feed(("$banner\r\n" + "t1231AA\r").toByteArray())

        assertEquals(banner, (events[0] as SlcanEvent.Text).text)
        assertEquals(0x123, (events[1] as SlcanEvent.Received).frame.id)
    }

    @Test
    fun `a stray LF does not poison the following line`() {
        val reader = SlcanReader()

        // The LF of a CRLF arrives at the head of the next line. Kept, it would make the frame
        // start with a character that is not 't', and the frame would be lost.
        val events = reader.feed("\r\nt4562BBCC\r".toByteArray())

        assertEquals(0x456, (events.last() as SlcanEvent.Received).frame.id)
    }

    @Test
    fun `an overlong line is reported rather than vanishing`() {
        val reader = SlcanReader()

        val events = reader.feed(("x".repeat(400) + "\r").toByteArray())

        assertTrue((events.single() as SlcanEvent.Text).text.contains("overlong"))
    }

    @Test
    fun `feed honours the reported length and ignores buffer tail`() {
        val reader = SlcanReader()
        val buffer = ByteArray(64)
        val line = "t1231AA\r".toByteArray()
        line.copyInto(buffer)

        // A bulk read returns fewer bytes than the buffer holds; the rest is stale.
        assertEquals(1, reader.feed(buffer, line.size).size)
    }
}
