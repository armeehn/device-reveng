package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stock's accOff / accOn as root commands, recorded instead of run. */
class AccStandbyTest {

    private class FakeShell(private val wifiOn: String) {
        val ran = mutableListOf<String>()

        fun run(command: String): RootShell.Result {
            ran.add(command)
            val out = if (command == AccStandby.WIFI_STATE) listOf(wifiOn) else emptyList()
            return RootShell.Result(0, out, emptyList())
        }
    }

    @Test
    fun enterDropsRadiosAndTheCameraGates() {
        val shell = FakeShell(wifiOn = "1")
        AccStandby(shell::run).enter()

        val all = shell.ran.joinToString("; ")
        assertTrue(all.contains("airplane-mode enable"))
        assertTrue(all.contains("setprop sys.acc.state 0"))
        assertTrue(all.contains("PowerManagerService.Display > /sys/power/wake_unlock"))
    }

    @Test
    fun leaveRestoresTheCameraBeforeTheDecoderThenRadios() {
        val shell = FakeShell(wifiOn = "1")
        val standby = AccStandby(shell::run, decoder = { shell.ran.add("decoder") })
        standby.enter()
        shell.ran.clear()

        standby.leave()

        assertEquals(listOf(AccStandby.CAMERA_ON, "decoder", AccStandby.RADIOS_ON, AccStandby.WIFI_ON), shell.ran)
    }

    @Test
    fun wifiComesBackOnlyIfItWasOn() {
        val shell = FakeShell(wifiOn = "0")
        val standby = AccStandby(shell::run)
        standby.enter()
        shell.ran.clear()

        standby.leave()

        assertTrue(shell.ran.none { it.contains(AccStandby.WIFI_ON) })
    }

    @Test
    fun darkenIsTheSleepKey() {
        val shell = FakeShell(wifiOn = "1")
        AccStandby(shell::run).darken()

        assertEquals(listOf(AccStandby.DARKEN), shell.ran)
    }
}
