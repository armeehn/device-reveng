package com.ripostelabs.carlauncher.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which app the nav card, the wheel NAV key and the NAV gesture open (RAV4-158). Stock keeps
 * the choice in `sys_custom_navi_app_pkg_cls_tab`; ours is the launcher's own setting, then the
 * vendor SysVar, then Maps. An app that is no longer installed must never brick the tap.
 */
class NavPickTest {

    private val waze = "com.waze"
    private val vendor = NavRepository.NavTarget("com.vendor.nav", "com.vendor.nav.Main")
    private val maps = NavRepository.NavTarget(NavRepository.MAPS_PACKAGE, null)

    @Test
    fun driverChoiceWins() {
        val t = NavRepository.pickTarget(chosen = waze, vendor = vendor) { true }

        assertEquals(NavRepository.NavTarget(waze, null), t)
    }

    @Test
    fun automaticFallsToTheVendorKeyThenMaps() {
        assertEquals(vendor, NavRepository.pickTarget(chosen = "", vendor = vendor) { true })
        assertEquals(maps, NavRepository.pickTarget(chosen = "", vendor = null) { true })
    }

    @Test
    fun uninstalledChoiceFallsThrough() {
        val t = NavRepository.pickTarget(chosen = waze, vendor = null) { it != waze }

        assertEquals(maps, t)
    }

    @Test
    fun nothingLaunchableLeavesTheGeoChooser() {
        assertNull(NavRepository.pickTarget(chosen = waze, vendor = null) { false })
    }
}
