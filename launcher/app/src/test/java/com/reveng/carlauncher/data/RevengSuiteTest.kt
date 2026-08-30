package com.reveng.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.5 — the suite registry is the launcher's only record of which apps form the suite. */
class RevengSuiteTest {

    @Test
    fun `registry has no duplicate packages`() {
        val packages = RevengSuite.APPS.map { it.packageName }
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test
    fun `every member carries the suite prefix`() {
        RevengSuite.APPS.forEach {
            assertTrue(it.packageName, it.packageName.startsWith(RevengSuite.PACKAGE_PREFIX))
        }
    }

    /**
     * The launcher shares the `com.reveng.` prefix with the suite, so a prefix match would
     * classify the launcher — and its `.debug` sibling — as one of its own apps. That would put
     * the launcher in its own suite folder and count it in the Setup Doctor's tally.
     */
    @Test
    fun `the launcher is not a suite app`() {
        assertFalse(RevengSuite.isSuiteApp("com.reveng.carlauncher"))
        assertFalse(RevengSuite.isSuiteApp("com.reveng.carlauncher.debug"))
        assertTrue(RevengSuite.isSuiteApp("com.reveng.clock"))
    }

    @Test
    fun `installed and missing partition the registry`() {
        val present = setOf("com.reveng.clock", "com.reveng.weather", "com.example.other")
        val installed = RevengSuite.installed(present)
        val missing = RevengSuite.missing(present)

        assertEquals(listOf("Clock", "Weather"), installed.map { it.label })
        assertEquals(RevengSuite.APPS.size, installed.size + missing.size)
        assertTrue(installed.none { it in missing })
    }

    @Test
    fun `nothing installed means the whole registry is missing`() {
        assertEquals(RevengSuite.APPS.size, RevengSuite.missing(emptySet()).size)
        assertTrue(RevengSuite.installed(emptySet()).isEmpty())
    }
}
