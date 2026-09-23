package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Diagnostics page and its "copy to log" dump both read [McuDiagnostics.report]; the shape
 * pinned here is what `rav4 car diag` finds in logcat.
 */
class McuDiagnosticsTest {

    private companion object {
        const val NOW = 100_000L
        const val WHEEL_CMD = 0x11
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun frame(cmd: Int, vararg payload: Int) = McuFrame.Decoded.Frame(cmd, bytes(*payload))

    private fun diag(capacity: Int = 8) = McuDiagnostics(capacity = capacity, now = { NOW })

    private fun info(rpm: Int) = CanSignal.VehicleInfo(rpm = rpm, speedRaw = 0, speedKmh = 0.0, coolantC = null)

    @Test
    fun relaysKeepTheLastN() {
        val d = diag(capacity = 3)
        (1..4).forEach { n -> d.onCanRelay(bytes(n), emptyList()) }

        val s = d.snapshot.value
        assertEquals(4L, s.relayCount)
        assertEquals(listOf(2, 3, 4), s.relays.map { it.body[0].toInt() })
    }

    @Test
    fun relayLineShowsHexAgeAndCut() {
        val d = diag()
        d.onCanRelay(bytes(0x5A, 0xA5, 0x03, WHEEL_CMD, 0x01, 0x02, 0x03, 0xC0), listOf(frame(WHEEL_CMD, 0x01, 0x02, 0x03)))
        d.onCanRelay(bytes(0x00, 0x5A), emptyList())
        d.onCanRelay(bytes(0x5A, 0xA5, 0x3D), listOf(McuFrame.Decoded.Malformed("len says 61, so 66 bytes, but got 3")))

        val lines = McuDiagnostics.Format.relayLines(d.snapshot.value.relays, NOW + 2_500)
        assertEquals("2 s  8 B  5A A5 03 11 01 02 03 C0  ->  cmd 0x11 (3 B)", lines[0])
        assertEquals("2 s  2 B  00 5A  ->  held", lines[1])
        assertEquals("2 s  3 B  5A A5 3D  ->  malformed: len says 61, so 66 bytes, but got 3", lines[2])
    }

    @Test
    fun relayCountersTellFramesFromMalformed() {
        val d = diag()
        d.onCanRelay(bytes(1), listOf(frame(WHEEL_CMD), McuFrame.Decoded.Malformed("x"), frame(0x32)))

        val s = d.snapshot.value
        assertEquals(2L, s.boxFrames)
        assertEquals(1L, s.boxMalformed)
    }

    @Test
    fun signalsKeepTheNewestPerTypeSortedByName() {
        val d = diag()
        d.onCanSignal(info(10), NOW - 5_000)
        d.onCanSignal(CanSignal.Version("HW-1.2"), NOW - 1_000)
        d.onCanSignal(info(20), NOW)

        val s = d.snapshot.value.signals
        assertEquals(listOf("VehicleInfo", "Version"), s.map { it.signal.javaClass.simpleName })
        assertEquals(20, (s[0].signal as CanSignal.VehicleInfo).rpm)
        assertEquals(NOW, s[0].atMs)
    }

    @Test
    fun ageIsHumanSized() {
        assertEquals("<1 s", McuDiagnostics.Format.age(NOW - 500, NOW))
        assertEquals("3 s", McuDiagnostics.Format.age(NOW - 3_400, NOW))
        assertEquals("2 min", McuDiagnostics.Format.age(NOW - 125_000, NOW))
        assertEquals("2 h", McuDiagnostics.Format.age(NOW - 7_200_000, NOW))
    }

    @Test
    fun unknownSignalShowsItsPayloadAsHex() {
        val line = McuDiagnostics.Format.signal(CanSignal.Unknown(0x7E, bytes(0xDE, 0xAD)))
        assertEquals("Unknown(opcode=0x7E, payload=DE AD)", line)
    }

    @Test
    fun statusLinesCoverEveryState() {
        assertEquals("idle", McuDiagnostics.Format.status(McuOwner.Status.Idle))
        assertEquals("blocked: eventcenter owns the port", McuDiagnostics.Format.status(McuOwner.Status.Blocked("eventcenter owns the port")))
        assertEquals("failed: open", McuDiagnostics.Format.status(McuOwner.Status.Failed("open")))
        assertEquals(
            "running, SRC_NULL acked, 12 frames, 1 bad CK, 7 bytes skipped",
            McuDiagnostics.Format.status(McuOwner.Status.Running(acked = true, frames = 12, badChecksum = 1, skipped = 7)),
        )
        assertEquals(
            "running, SRC_NULL not acked, 0 frames, 0 bad CK, 0 bytes skipped",
            McuDiagnostics.Format.status(McuOwner.Status.Running(acked = false, frames = 0, badChecksum = 0, skipped = 0)),
        )
    }

    @Test
    fun reportCarriesLinkVersionCarRelaysAndSignals() {
        val d = diag()
        d.onMcuVersion("RL78-2.1")
        d.onCanBoxCar(CarProfiles.DEFAULT)
        d.onCanRelay(bytes(0x5A, 0xA5, 0x00, WHEEL_CMD, 0xEE), listOf(frame(WHEEL_CMD)))
        d.onCanSignal(CanSignal.Version("HW-1.2"), NOW)

        val report = d.report(McuOwner.Status.Running(acked = true, frames = 3, badChecksum = 0, skipped = 0), NOW + 1_000)

        assertEquals("link: running, SRC_NULL acked, 3 frames, 0 bad CK, 0 bytes skipped", report[0])
        assertEquals("mcu version: RL78-2.1", report[1])
        assertEquals("can box car: ${CarProfiles.DEFAULT.label} (0x%02X), told 1 s ago".format(CarProfiles.DEFAULT.carType), report[2])
        assertEquals("relays: 1, box frames: 1, malformed: 0", report[3])
        assertTrue(report.contains("relay: 1 s  5 B  5A A5 00 11 EE  ->  cmd 0x11 (0 B)"))
        assertTrue(report.contains("signal: 1 s  Version(text=HW-1.2)"))
    }

    @Test
    fun reportSaysWhatIsUnknownYet() {
        val report = diag().report(McuOwner.Status.Idle, NOW)

        assertEquals("mcu version: unknown", report[1])
        assertEquals("can box car: not told yet", report[2])
        assertNull(diag().snapshot.value.mcuVersion)
    }
}
