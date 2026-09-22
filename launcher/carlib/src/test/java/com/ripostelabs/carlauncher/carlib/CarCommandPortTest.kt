package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** Helm's write side: the allow-list is the contract, so every refusal is a test. */
class CarCommandPortTest {
    private val calls = mutableListOf<String>()

    private val target = object : CarCommandPort.Target {
        override fun setVolume(level: Int) { calls += "volume=$level" }
        override fun setMute(on: Boolean) { calls += "mute=$on" }
        override fun setMode(mode: McuOwnerProtocol.Mode): Boolean {
            calls += "mode=${mode.name}"
            return mode != McuOwnerProtocol.Mode.AUX
        }
    }

    private val port = CarCommandPort(target)

    @Test
    fun volumeAndMuteReachTheTarget() {
        assertTrue(port.handle("""{"cmd":"volume","level":12}""").getBoolean("ok"))
        assertTrue(port.handle("""{"cmd":"mute","on":true}""").getBoolean("ok"))
        assertEquals(listOf("volume=12", "mute=true"), calls)
    }

    @Test
    fun allowedModeIsSentAndAckIsReported() {
        assertTrue(port.handle("""{"cmd":"mode","mode":"RADIO"}""").getBoolean("ok"))

        val nack = port.handle("""{"cmd":"mode","mode":"AUX"}""")
        assertFalse(nack.getBoolean("ok"))
        assertEquals("mode not acknowledged: AUX", nack.getString("error"))
        assertEquals(listOf("mode=RADIO", "mode=AUX"), calls)
    }

    @Test
    fun powerOffAndUnknownModesNeverReachTheTarget() {
        for (name in listOf("POWER_OFF", "MCU_VERSION", "BACKCAR", "nonsense")) {
            val reply = port.handle("""{"cmd":"mode","mode":"$name"}""")
            assertFalse(reply.getBoolean("ok"))
            assertEquals("mode not allowed: $name", reply.getString("error"))
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun unknownCommandAndBadJsonAreRefused() {
        assertEquals("unknown cmd: reboot", port.handle("""{"cmd":"reboot"}""").getString("error"))
        assertFalse(port.handle("not json").getBoolean("ok"))
        assertFalse(port.handle("""{"cmd":"volume"}""").getBoolean("ok"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun takenPortLeavesTheLauncherStanding() {
        // MainActivity.onCreate calls start(); an unguarded bind took the whole launcher down
        // when a second build held the port.
        val holder = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            val blocked = CarCommandPort(target, port = holder.localPort)
            blocked.start()
            blocked.stop()
        } finally {
            holder.close()
        }
    }
}
