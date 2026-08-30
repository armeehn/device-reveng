package com.ripostelabs.carlauncher.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SysVar Set_NavPackageName / Set_NavClassName are free-text values another app wrote, so the
 * normalisation must cope with blanks, stray whitespace, and both class-name spellings — and
 * must yield null (→ Maps fallback) rather than a half-built target.
 */
class NavTargetTest {

    @Test
    fun packageAndFullClass() {
        assertEquals(
            NavRepository.NavTarget("com.nav.app", "com.nav.app.MainActivity"),
            NavRepository.vendorNavTarget("com.nav.app", "com.nav.app.MainActivity"),
        )
    }

    @Test
    fun relativeClassResolvesAgainstPackage() {
        assertEquals(
            NavRepository.NavTarget("com.nav.app", "com.nav.app.ui.MapActivity"),
            NavRepository.vendorNavTarget("com.nav.app", ".ui.MapActivity"),
        )
    }

    @Test
    fun packageOnlyKeepsClassNull() {
        assertEquals(
            NavRepository.NavTarget("com.nav.app", null),
            NavRepository.vendorNavTarget("com.nav.app", null),
        )
        assertEquals(
            NavRepository.NavTarget("com.nav.app", null),
            NavRepository.vendorNavTarget("com.nav.app", "  "),
        )
    }

    @Test
    fun noPackageMeansNoTarget() {
        assertNull(NavRepository.vendorNavTarget(null, "com.nav.app.MainActivity"))
        assertNull(NavRepository.vendorNavTarget("", null))
        assertNull(NavRepository.vendorNavTarget("   ", ".MapActivity"))
    }

    @Test
    fun valuesAreTrimmed() {
        assertEquals(
            NavRepository.NavTarget("com.nav.app", "com.nav.app.MainActivity"),
            NavRepository.vendorNavTarget(" com.nav.app ", " com.nav.app.MainActivity "),
        )
    }
}
