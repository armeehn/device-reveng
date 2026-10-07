package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stock's accOff / accOn as root commands, recorded instead of run. */
class AccStandbyTest {

    private class FakeShell(
        private val wifiOn: String,
        private val usbRole: String = "host",
        private val btOn: String = "1",
        private val location: String = "3",
        private val airplane: String = "0",
        var marker: String = "",
    ) {
        val ran = mutableListOf<String>()

        fun run(command: String): RootShell.Result {
            ran.add(command)

            // The marker is a persist property: it outlives the process, like a reboot.
            if (command.startsWith(AccStandby.MARKER_SET)) {
                marker = command.removePrefix(AccStandby.MARKER_SET).trim('\'')
            }

            val out = when (command) {
                AccStandby.WIFI_STATE -> listOf(wifiOn)
                AccStandby.BT_STATE -> listOf(btOn)
                AccStandby.LOCATION_STATE -> listOf(location)
                AccStandby.AIRPLANE_STATE -> listOf(airplane)
                AccStandby.MARKER_GET -> listOf(marker)
                UsbRole.readCommand() -> listOf(usbRole)
                else -> emptyList()
            }
            return RootShell.Result(0, out, emptyList())
        }

        /** Only the radio commands, in order. */
        fun radios(): List<String> = ran.filter { it in RESTORE_WORDS || it.startsWith(LOCATION_PUT) }

        companion object {
            private const val LOCATION_PUT = "settings put secure location_mode"
            private val RESTORE_WORDS = setOf(
                AccStandby.AIRPLANE_OFF, AccStandby.BT_ON, AccStandby.WIFI_ON,
            )
        }
    }

    @Test
    fun enterSavesTheMarkerBeforeTheRadiosGoOff() {
        val shell = FakeShell(wifiOn = "1")
        AccStandby(shell::run).enter()

        val saved = shell.ran.indexOfFirst { it.startsWith(AccStandby.MARKER_SET) }
        assertTrue(saved >= 0)
        assertTrue(saved < shell.ran.indexOf(AccStandby.RADIOS_OFF))
        assertEquals("1,1,3,0", shell.marker)
    }

    @Test
    fun aBootAfterAFailedWakeRestoresTheRadios() {
        // Standby entered, then RST or a B+ cut: leave never ran, a new process starts.
        val shell = FakeShell(wifiOn = "1")
        AccStandby(shell::run).enter()
        shell.ran.clear()

        AccStandby(shell::run).recover()

        assertEquals(
            listOf(AccStandby.AIRPLANE_OFF, "settings put secure location_mode 3", AccStandby.BT_ON, AccStandby.WIFI_ON),
            shell.radios(),
        )
        assertEquals("", shell.marker)
    }

    @Test
    fun aRadioTheOwnerHadOffStaysOffAtBoot() {
        val shell = FakeShell(wifiOn = "0", btOn = "0", location = "0", airplane = "1")
        AccStandby(shell::run).enter()
        shell.ran.clear()

        AccStandby(shell::run).recover()

        assertEquals(emptyList<String>(), shell.radios())
        assertEquals("", shell.marker)
    }

    @Test
    fun onlyBluetoothComesBackWhenOnlyBluetoothWasOn() {
        val shell = FakeShell(wifiOn = "0", btOn = "1", location = "0", airplane = "0")
        AccStandby(shell::run).enter()
        shell.ran.clear()

        AccStandby(shell::run).recover()

        assertEquals(listOf(AccStandby.AIRPLANE_OFF, AccStandby.BT_ON), shell.radios())
    }

    // StandbyFallback reads this with the power-on reason: RST after it means the wake failed.
    @Test
    fun recoverSaysWhetherAStandbyWasLeft() {
        val shell = FakeShell(wifiOn = "1")
        AccStandby(shell::run).enter()

        assertEquals(StandbyFallback.Left.NEVER, AccStandby(shell::run).recover())
        assertEquals(StandbyFallback.Left.YES, AccStandby(shell::run).recover())
    }

    /** Shutdowns the light depth scheduled: delay and the job, run by hand. */
    private class FakeLater {
        val delays = mutableListOf<Long>()
        val jobs = mutableListOf<() -> Unit>()
        var cancelled = 0

        fun schedule(delayMs: Long, job: () -> Unit): () -> Unit {
            delays += delayMs
            jobs += job
            return { cancelled++ }
        }
    }

    private fun light(shell: FakeShell, later: FakeLater) =
        AccStandby(shell::run, depth = { AccStandby.Depth.LIGHT }, later = later::schedule)

    // Bench 2026-10-07: an s2idle suspend never resumes, not even on an RTC alarm. LIGHT keeps
    // the kernel awake (the Display lock stays held) and leaves USB alone, so nothing suspends.
    @Test
    fun lightKeepsTheKernelAwakeAndUsbAlone() {
        val shell = FakeShell(wifiOn = "1", usbRole = "peripheral")
        light(shell, FakeLater()).enter()

        assertTrue(AccStandby.RADIOS_OFF in shell.ran)
        assertTrue(AccStandby.CAMERA_GATE_OFF in shell.ran)
        assertFalse(AccStandby.CAMERA_OFF in shell.ran)
        assertFalse(shell.ran.any { "wake_unlock" in it })
        assertFalse(AccStandby.USB_HOST in shell.ran)
        assertFalse(AccStandby.USB_POWER_OFF in shell.ran)
    }

    // ~1 A awake on the bench: warm for LIGHT_MAX_MS, then shut down to spare the battery.
    @Test
    fun lightShutsDownAfterItsWindow() {
        val shell = FakeShell(wifiOn = "1")
        val later = FakeLater()
        light(shell, later).enter()

        assertEquals(listOf(AccStandby.LIGHT_MAX_MS), later.delays)
        later.jobs.single().invoke()
        assertTrue(AccStandby.SHUTDOWN in shell.ran)
    }

    @Test
    fun anAccOnInsideTheWindowCancelsTheShutdown() {
        val shell = FakeShell(wifiOn = "1")
        val later = FakeLater()
        val standby = light(shell, later)
        standby.enter()
        standby.leave()

        assertEquals(1, later.cancelled)
        assertFalse(AccStandby.USB_POWER_ON in shell.ran)
        assertEquals("", shell.marker)
    }

    @Test
    fun aBootWithoutTheMarkerTouchesNothing() {
        val shell = FakeShell(wifiOn = "1")
        AccStandby(shell::run).recover()

        assertEquals(listOf(AccStandby.MARKER_GET), shell.ran)
    }

    @Test
    fun aSecondEnterKeepsTheFirstSavedStates() {
        // The radios are already off from the first standby; saving them again would lose the owner's.
        val shell = FakeShell(wifiOn = "0", btOn = "0", location = "0", airplane = "1", marker = "1,1,3,0")
        AccStandby(shell::run).enter()

        assertEquals("1,1,3,0", shell.marker)
    }

    @Test
    fun leaveClearsTheMarker() {
        val shell = FakeShell(wifiOn = "1")
        val standby = AccStandby(shell::run)
        standby.enter()
        standby.leave()

        assertEquals("", shell.marker)
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
            listOf(AccStandby.USB_POWER_ON, AccStandby.CAMERA_ON, "decoder") + RESTORED,
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
                AccStandby.CAMERA_ON, "decoder",
            ) + RESTORED,
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

    private companion object {
        /** What leave runs after the decoder when every radio was on before standby. */
        val RESTORED = listOf(
            AccStandby.AIRPLANE_OFF, "settings put secure location_mode 3", AccStandby.BT_ON, AccStandby.WIFI_ON,
            AccStandby.MARKER_CLEAR,
        )
    }
}
