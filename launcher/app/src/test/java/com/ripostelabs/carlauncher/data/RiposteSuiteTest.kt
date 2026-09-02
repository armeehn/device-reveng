package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.5 — the suite registry is the launcher's only record of which apps form the suite. */
class RiposteSuiteTest {

    @Test
    fun `registry has no duplicate packages`() {
        val packages = RiposteSuite.APPS.map { it.packageName }
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test
    fun `every member carries the suite prefix`() {
        RiposteSuite.APPS.forEach {
            assertTrue(it.packageName, it.packageName.startsWith(RiposteSuite.PACKAGE_PREFIX))
        }
    }

    /**
     * The launcher shares the `com.ripostelabs.` prefix with the suite, so a prefix match would
     * classify the launcher — and its `.debug` sibling — as one of its own apps. That would put
     * the launcher in its own suite folder and count it in the Setup Doctor's tally.
     */
    @Test
    fun `the launcher is not a suite app`() {
        assertFalse(RiposteSuite.isSuiteApp("com.ripostelabs.carlauncher"))
        assertFalse(RiposteSuite.isSuiteApp("com.ripostelabs.carlauncher.debug"))
        assertTrue(RiposteSuite.isSuiteApp("com.ripostelabs.clock"))
    }

    @Test
    fun `installed and missing partition the registry`() {
        val present = setOf("com.ripostelabs.clock", "com.ripostelabs.weather", "com.example.other")
        val installed = RiposteSuite.installed(present)
        val missing = RiposteSuite.missing(present)

        assertEquals(listOf("Clock", "Weather"), installed.map { it.label })
        assertEquals(RiposteSuite.APPS.size, installed.size + missing.size)
        assertTrue(installed.none { it in missing })
    }

    @Test
    fun `nothing installed means the whole registry is missing`() {
        assertEquals(RiposteSuite.APPS.size, RiposteSuite.missing(emptySet()).size)
        assertTrue(RiposteSuite.installed(emptySet()).isEmpty())
    }
}
