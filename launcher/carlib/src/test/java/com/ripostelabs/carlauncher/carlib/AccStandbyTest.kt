package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stock's accOff / accOn as root commands, recorded instead of run. */
class AccStandbyTest {

    private class FakeShell(private val wifiOn: String, private val usbRole: String = "host") {
        val ran = mutableListOf<String>()

        fun run(command: String): RootShell.Result {
            ran.add(command)
            val out = when (command) {
                AccStandby.WIFI_STATE -> listOf(wifiOn)
                UsbRole.readCommand() -> listOf(usbRole)
                else -> emptyList()
            }
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

        assertEquals(
            listOf(AccStandby.USB_POWER_ON, AccStandby.CAMERA_ON, "decoder", AccStandby.RADIOS_ON, AccStandby.WIFI_ON),
            shell.ran,
        )
    }

    @Test
    fun enterTurnsAPeripheralPortToHostThenCutsUsbPower() {
        val shell = FakeShell(wifiOn = "1", usbRole = "peripheral")
        AccStandby(shell::run).enter()

        val tail = shell.ran.takeLast(3)
        assertEquals(listOf(UsbRole.readCommand(), AccStandby.USB_HOST, AccStandby.USB_POWER_OFF), tail)
        assertTrue(shell.ran.indexOf(AccStandby.CAMERA_OFF) < shell.ran.indexOf(AccStandby.USB_HOST))
    }

    @Test
    fun enterLeavesAHostPortAloneButStillCutsUsbPower() {
        val shell = FakeShell(wifiOn = "1", usbRole = "host")
        AccStandby(shell::run).enter()

        assertTrue(shell.ran.none { it == AccStandby.USB_HOST })
        assertEquals(AccStandby.USB_POWER_OFF, shell.ran.last())
    }

    @Test
    fun enterNeverTouchesTheStoredRole() {
        val shell = FakeShell(wifiOn = "1", usbRole = "peripheral")
        val standby = AccStandby(shell::run)
        standby.enter()
        standby.leave()

        assertTrue(shell.ran.none { it.contains(UsbRole.ROLE_PROP) })
    }

    @Test
    fun leaveRestoresUsbPowerAndThePeripheralRoleFirst() {
        val shell = FakeShell(wifiOn = "1", usbRole = "peripheral")
        val standby = AccStandby(shell::run, decoder = { shell.ran.add("decoder") })
        standby.enter()
        shell.ran.clear()

        standby.leave()

        assertEquals(
            listOf(
                AccStandby.USB_POWER_ON, AccStandby.USB_PERIPHERAL,
                AccStandby.CAMERA_ON, "decoder", AccStandby.RADIOS_ON, AccStandby.WIFI_ON,
            ),
            shell.ran,
        )
    }

    @Test
    fun aSecondLeaveDoesNotRewriteTheRole() {
        val shell = FakeShell(wifiOn = "1", usbRole = "peripheral")
        val standby = AccStandby(shell::run)
        standby.enter()
        standby.leave()
        shell.ran.clear()

        standby.leave()

        assertTrue(shell.ran.none { it == AccStandby.USB_PERIPHERAL })
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
