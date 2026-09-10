package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The response id range carries every ECU's answer to every PID, so the parser's job is mostly
 * to say NO. A reply to the wrong PID, a refusal, or a frame from elsewhere on the bus must never
 * come out as a speed — this number is what the speed candidates get calibrated against, and a
 * wrong reference is worse than none.
 *
 * Frames are built from byte lists, not typed as hex. Two hand-typed payloads in this project
 * have already come out a byte long and been rejected by the strict codec; the codec was right
 * both times, and the lesson is not to hand-count.
 */
class ObdTest {

    private fun ecu(id: Int, vararg bytes: Int) = SlcanFrame(id, bytes.toList())

    @Test
    fun `the request is the frame the shell probe sent from the car`() {
        // Verbatim from headunit-drive-capture.sh, which the car accepted on 2026-09-08.
        assertEquals("t7DF802010D0000000000\r", String(SlcanCodec.transmit(Obd.request(ObdPid.SPEED_KMH))))
    }

    @Test
    fun `a positive reply yields the ECU's km per hour`() {
        val reply = Obd.parse(ecu(0x7E8, 0x03, 0x41, 0x0D, 0x36, 0, 0, 0, 0))

        assertEquals(Obd.Reply.Value(pid = ObdPid.SPEED_KMH, value = (0x36).toDouble(), ecu = 0x7E8), reply)
    }

    @Test
    fun `zero is a real answer, not silence`() {
        val reply = Obd.parse(ecu(0x7E8, 0x03, 0x41, 0x0D, 0x00, 0, 0, 0, 0))

        assertEquals(Obd.Reply.Value(pid = ObdPid.SPEED_KMH, value = (0).toDouble(), ecu = 0x7E8), reply)
    }

    @Test
    fun `the top of the range survives the signed-byte trip`() {
        // 255 km/h is 0xFF, the value a careless conversion turns into -1.
        val reply = Obd.parse(ecu(0x7E8, 0x03, 0x41, 0x0D, 0xFF, 0, 0, 0, 0))

        assertEquals(255, (reply as Obd.Reply.Value).value.toInt())
    }

    @Test
    fun `any ECU in the response range is accepted`() {
        val reply = Obd.parse(ecu(0x7EF, 0x03, 0x41, 0x0D, 0x50, 0, 0, 0, 0))

        assertEquals(0x7EF, (reply as Obd.Reply.Value).ecu)
    }

    @Test
    fun `a refusal is reported as a refusal`() {
        // 7F 01 12: service 01 refused, NRC 0x12 (sub-function not supported).
        val reply = Obd.parse(ecu(0x7E8, 0x03, 0x7F, 0x01, 0x12, 0, 0, 0, 0))

        assertEquals(Obd.Reply.Refused(nrc = 0x12, ecu = 0x7E8), reply)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a reply to the RPM PID is not a speed`() {
        // 41 0C is engine RPM, one PID away. Read as speed it would report 0x1A km/h.
        assertNull(Obd.parse(ecu(0x7E8, 0x04, 0x41, 0x0C, 0x1A, 0xF0, 0, 0, 0)))
        assertNull(Obd.parse(ecu(0x7E8, 0x03, 0x41, 0x0C, 0x1A, 0, 0, 0, 0)))
    }

    @Test
    fun `a refusal never surfaces as a speed`() {
        val reply = Obd.parse(ecu(0x7E8, 0x03, 0x7F, 0x01, 0x12, 0, 0, 0, 0))

        assertFalse(reply is Obd.Reply.Value)
    }

    @Test
    fun `a body-bus frame with the right bytes is ignored`() {
        // Identical payload to a valid reply, wrong id. The door frame's id is not an ECU.
        assertNull(Obd.parse(ecu(0x4A5, 0x03, 0x41, 0x0D, 0x36, 0, 0, 0, 0)))
    }

    @Test
    fun `an id just outside the ECU range is ignored`() {
        assertNull(Obd.parse(ecu(0x7E7, 0x03, 0x41, 0x0D, 0x36, 0, 0, 0, 0)))
        assertNull(Obd.parse(ecu(0x7F0, 0x03, 0x41, 0x0D, 0x36, 0, 0, 0, 0)))
    }

    @Test
    fun `a wrong PCI length is not trusted`() {
        // A length byte of 2 cannot carry service, PID and a value.
        assertNull(Obd.parse(ecu(0x7E8, 0x02, 0x41, 0x0D, 0x36, 0, 0, 0, 0)))
    }

    @Test
    fun `a short frame is not indexed`() {
        assertNull(Obd.parse(ecu(0x7E8, 0x03, 0x41)))
    }

    @Test
    fun `the request is the only frame this object can build`() {
        // There is exactly one transmit path onto the vehicle bus, and this is it.
        assertEquals(Obd.REQUEST_ID, Obd.request(ObdPid.SPEED_KMH).id)
        assertEquals(8, Obd.request(ObdPid.SPEED_KMH).data.size)
    }

    // ── Poller ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the first ask always sends`() {
        assertTrue(ObdPoller(intervalMs = 500).shouldSend(now = 1_000))
    }

    @Test
    fun `a second ask inside the interval is refused`() {
        val poller = ObdPoller(intervalMs = 500)
        poller.shouldSend(now = 1_000)

        assertFalse(poller.shouldSend(now = 1_499))
    }

    @Test
    fun `the interval elapsing allows the next send`() {
        val poller = ObdPoller(intervalMs = 500)
        poller.shouldSend(now = 1_000)

        assertTrue(poller.shouldSend(now = 1_500))
    }

    @Test
    fun `a read loop at bus rate does not spam the bus`() {
        // ~1215 turns in one simulated second: two sends, not twelve hundred.
        val poller = ObdPoller(intervalMs = 500)
        var sent = 0
        for (i in 0 until 1_215) {
            if (poller.shouldSend(now = 1_000L + i * 1_000L / 1_215L)) sent++
        }

        assertEquals(2, sent)
    }
}
