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

    @Test
    fun theServicePathRebootsWithoutRoot() {
        val ok = PowerActions.reboot(PowerActions.Path.SERVICE, vendor = {}, root = root(0), service = { true })

        assertTrue(ok)
        assertEquals(emptyList<String>(), rootCommands)
    }

    // A service below API 3 or a dead binder answers false: root still reboots.
    @Test
    fun aServiceThatCannotRebootFallsBackToRoot() {
        val ok = PowerActions.reboot(PowerActions.Path.SERVICE, vendor = {}, root = root(0), service = { false })

        assertTrue(ok)
        assertEquals(listOf(PowerActions.ROOT_REBOOT), rootCommands)
    }

    @Test
    fun factoryResetGoesToTheService() {
        var wipes = 0
        val ok = PowerActions.factoryReset(PowerActions.Path.SERVICE, vendor = { vendorCalls += "reset" }, service = { wipes++; true })

        assertTrue(ok)
        assertEquals(1, wipes)
        assertEquals(emptyList<String>(), vendorCalls)
    }

    // 0.2 without the service has no system uid to wipe with: nothing happens, and it says so.
    @Test
    fun factoryResetWithoutTheServiceDoesNothing() {
        val owner = PowerActions.factoryReset(PowerActions.Path.OWNER, vendor = { vendorCalls += "reset" }, service = { true })
        val oldService = PowerActions.factoryReset(PowerActions.Path.SERVICE, vendor = { vendorCalls += "reset" }, service = { false })

        assertFalse(owner)
        assertFalse(oldService)
        assertEquals(emptyList<String>(), vendorCalls)
        assertEquals(emptyList<String>(), rootCommands)
    }

    @Test
    fun factoryResetOnStockAsksEventcenter() {
        val ok = PowerActions.factoryReset(PowerActions.Path.VENDOR, vendor = { vendorCalls += "reset" }, service = { false })

        assertTrue(ok)
        assertEquals(listOf("reset"), vendorCalls)
    }
}
