package com.ripostelabs.carlauncher.carlib

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Helm reads these lines over `adb forward`; the shape here is the contract. */
class McuStateExportTest {
    private val export = McuStateExport(port = 0, now = { 1_000L }).also { it.start() }

    @After
    fun stop() = export.stop()

    private fun client(): BufferedReader {
        val socket = Socket("127.0.0.1", export.boundPort)
        socket.soTimeout = 2_000
        return BufferedReader(InputStreamReader(socket.getInputStream()))
    }

    private fun sysEvent(acc: Boolean) = McuOwnerProtocol.SysEvent(
        disc = false, usb = false, rightTurn = false, illumination = false, brake = false,
        reverse = false, accLine = acc, mcan = false, startStop = false, hdmi = false, leftTurn = false,
    )

    @Test
    fun liveEventIsOneJsonLine() {
        val reader = client()
        Thread.sleep(100) // let the acceptor attach the client before the first frame

        export.onMainVolume(McuOwnerProtocol.MainVolume(level = 12, silent = false))

        val line = JSONObject(reader.readLine())
        assertEquals("mainVolume", line.getString("event"))
        assertEquals(1_000L, line.getLong("atMs"))
        assertEquals(12, line.getInt("level"))
        assertFalse(line.getBoolean("silent"))
    }

    @Test
    fun newClientGetsTheLastOfEveryEvent() {
        export.onSysEvent(sysEvent(acc = false))
        export.onSysEvent(sysEvent(acc = true))
        export.onRadio(McuOwnerProtocol.RadioEvent.Frequency(freq = 10130))
        export.onRadio(McuOwnerProtocol.RadioEvent.StationName(name = "CKOV"))

        val reader = client()
        val lines = (1..3).map { JSONObject(reader.readLine()) }

        assertEquals("sysEvent", lines[0].getString("event"))
        assertTrue(lines[0].getBoolean("accLine"))
        assertEquals("Frequency", lines[1].getString("type"))
        assertEquals(10130, lines[1].getInt("freq"))
        assertEquals("StationName", lines[2].getString("type"))
        assertEquals("CKOV", lines[2].getString("name"))
    }

    @Test
    fun canSignalCarriesItsTypeAndNulls() {
        export.onCanSignal(CanSignal.VehicleInfo(rpm = 800, speedRaw = 0, speedKmh = 0.0, coolantC = null), atMs = 5L)

        val line = JSONObject(client().readLine())
        assertEquals("canSignal", line.getString("event"))
        assertEquals("VehicleInfo", line.getString("type"))
        assertEquals(800, line.getInt("rpm"))
        assertTrue(line.isNull("coolantC"))
    }

    @Test
    fun takenPortLeavesTheLauncherStanding() {
        // MainActivity.onCreate calls start(); an unguarded bind took the whole launcher down
        // when a second build held the port (farm, 2026-09-22).
        val holder = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            val blocked = McuStateExport(port = holder.localPort, now = { 1_000L })
            blocked.start()
            blocked.onSysEvent(sysEvent(acc = true))
            blocked.stop()
        } finally {
            holder.close()
        }
    }
}
