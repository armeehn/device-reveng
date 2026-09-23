package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one USB controller's role: what the node says, which role a build starts in, and when a
 * switch is written at all. The shell is a fake, so no root and no framework is involved.
 */
class UsbRoleTest {

    private fun ok(out: String = "") = RootShell.Result(0, listOf(out), emptyList())
    private fun fail(code: Int = 1) = RootShell.Result(code, emptyList(), listOf("boom"))

    @Test
    fun parsesWhatTheNodeSays() {
        assertEquals(UsbRole.HOST, UsbRole.parse("host\n"))
        assertEquals(UsbRole.PERIPHERAL, UsbRole.parse(" peripheral "))
        assertEquals(UsbRole.UNKNOWN, UsbRole.parse("none"))
        assertEquals(UsbRole.UNKNOWN, UsbRole.parse(""))
        assertEquals(UsbRole.UNKNOWN, UsbRole.parse(null))
    }

    @Test
    fun wireWordsAreTheVendorsOwn() {
        // Utils.java:193 `openAdb(z ? "peripheral" : "host")`.
        assertEquals("host", UsbRole.HOST.wire)
        assertEquals("peripheral", UsbRole.PERIPHERAL.wire)
        assertEquals("/sys/devices/platform/soc/4e00000.ssusb/mode", UsbRole.MODE_NODE)
    }

    @Test
    fun benchImageDefaultsToPeripheralCarImageToHost() {
        assertEquals(UsbRole.PERIPHERAL, UsbRole.default(benchProp = "1"))
        assertEquals(UsbRole.HOST, UsbRole.default(benchProp = "0"))
        assertEquals(UsbRole.HOST, UsbRole.default(benchProp = ""))
        assertEquals(UsbRole.HOST, UsbRole.default(benchProp = null))
    }

    @Test
    fun storedChoiceWinsOverTheImageDefault() {
        assertEquals(UsbRole.HOST, UsbRole.resolve(stored = UsbRole.HOST, benchProp = "1"))
        assertEquals(UsbRole.PERIPHERAL, UsbRole.resolve(stored = null, benchProp = "1"))
        assertEquals(UsbRole.HOST, UsbRole.resolve(stored = UsbRole.UNKNOWN, benchProp = "0"))
    }

    @Test
    fun storedNameRoundTripsAndJunkIsNoChoice() {
        assertEquals(UsbRole.PERIPHERAL, UsbRole.choice("PERIPHERAL"))
        assertNull(UsbRole.choice("UNKNOWN"))
        assertNull(UsbRole.choice("garbage"))
        assertNull(UsbRole.choice(null))
    }

    @Test
    fun readAnswersUnknownWhenTheShellCannotSay() {
        assertEquals(UsbRole.HOST, UsbRole.read { ok("host") })
        assertEquals(UsbRole.UNKNOWN, UsbRole.read { fail() })
        assertEquals(UsbRole.UNKNOWN, UsbRole.read { null })
    }

    @Test
    fun noWriteWhenTheNodeAlreadyMatches() {
        val ran = mutableListOf<String>()

        val outcome = UsbRole.apply(UsbRole.HOST, rootAvailable = true, current = UsbRole.HOST) { ran += it; ok() }

        assertEquals(UsbRole.Switch.UNCHANGED, outcome)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun neverWritesUnknown() {
        val ran = mutableListOf<String>()

        val outcome = UsbRole.apply(UsbRole.UNKNOWN, rootAvailable = true, current = UsbRole.PERIPHERAL) { ran += it; ok() }

        assertEquals(UsbRole.Switch.REFUSED, outcome)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun noRootMeansNoWrite() {
        val ran = mutableListOf<String>()

        val outcome = UsbRole.apply(UsbRole.HOST, rootAvailable = false, current = UsbRole.PERIPHERAL) { ran += it; ok() }

        assertEquals(UsbRole.Switch.NO_ROOT, outcome)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun switchWritesTheNodeAndPersistsTheChoice() {
        val ran = mutableListOf<String>()

        val outcome = UsbRole.apply(UsbRole.HOST, rootAvailable = true, current = UsbRole.PERIPHERAL) { ran += it; ok() }

        assertEquals(UsbRole.Switch.WRITTEN, outcome)
        val command = ran.single()
        assertTrue(command, command.contains("> '${UsbRole.MODE_NODE}'"))
        assertTrue(command, command.contains("setprop ${UsbRole.ROLE_PROP} host"))
        assertTrue(command, command.contains("printf %s 'host'"))
    }

    @Test
    fun peripheralAsksForTheAdbGadgetFirst() {
        // riposte-usbadb.sh:7 sets sys.usb.config=adb before the switch, so the pigtail answers.
        val ran = mutableListOf<String>()

        UsbRole.apply(UsbRole.PERIPHERAL, rootAvailable = true, current = UsbRole.UNKNOWN) { ran += it; ok() }

        val command = ran.single()
        assertTrue(command, command.indexOf("setprop sys.usb.config adb") < command.indexOf("printf %s 'peripheral'"))
    }

    @Test
    fun aFailedWriteIsReportedNotHidden() {
        val outcome = UsbRole.apply(UsbRole.HOST, rootAvailable = true, current = UsbRole.PERIPHERAL) { fail() }

        assertEquals(UsbRole.Switch.FAILED, outcome)
    }
}
