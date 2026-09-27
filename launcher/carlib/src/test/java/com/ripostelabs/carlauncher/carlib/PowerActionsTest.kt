package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings > System & about > Reboot did nothing on Riposte OS 0.2 (bench, 2026-09-27): it only
 * asked the vendor's eventcenter, which 0.2 removes. With our MCU owner live the reboot goes
 * through the root shell; the vendor call stays for stock.
 */
class PowerActionsTest {

    private val vendorCalls = mutableListOf<String>()
    private val rootCommands = mutableListOf<String>()

    private fun root(code: Int): (String) -> RootShell.Result? = { cmd ->
        rootCommands += cmd
        RootShell.Result(code, emptyList(), emptyList())
    }

    @Test
    fun theOwnerPathRebootsThroughTheRootShell() {
        val ok = PowerActions.reboot(PowerActions.Path.OWNER, vendor = { vendorCalls += "reboot" }, root = root(0))

        assertTrue(ok)
        assertEquals(listOf(PowerActions.ROOT_REBOOT), rootCommands)
        assertEquals(emptyList<String>(), vendorCalls)
    }

    @Test
    fun theVendorPathAsksEventcenter() {
        val ok = PowerActions.reboot(PowerActions.Path.VENDOR, vendor = { vendorCalls += "reboot" }, root = root(0))

        assertTrue(ok)
        assertEquals(listOf("reboot"), vendorCalls)
        assertEquals(emptyList<String>(), rootCommands)
    }

    @Test
    fun aRefusedRootShellIsReportedNotSwallowed() {
        val ok = PowerActions.reboot(PowerActions.Path.OWNER, vendor = {}, root = root(1))

        assertFalse(ok)
    }
}
