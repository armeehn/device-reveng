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
    /** Riposte OS 0.2: Radio and Bluetooth bind a gateway that is not there; nothing else hides. */
    @Test
    fun `owner active hides only the vendor-bound members`() {
        assertEquals(
            setOf("com.ripostelabs.radio", "com.ripostelabs.bluetooth"),
            RiposteSuite.hiddenOnOwner(ownerActive = true),
        )
        assertTrue(RiposteSuite.hiddenOnOwner(ownerActive = false).isEmpty())
        RiposteSuite.VENDOR_BOUND.forEach { assertTrue(it, RiposteSuite.isSuiteApp(it)) }
    }

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

    /**
     * The suite shipped once as `com.reveng.*`. Those packages query a theme authority the
     * launcher no longer publishes, so they keep their built-in look no matter which theme is
     * active — and they carry the same label and icon as the live rewrite beside them. A
     * retired twin is only shadowed once its `com.ripostelabs.*` replacement is installed;
     * with no replacement it is still the only Clock the owner has.
     */
    @Test
    fun `a retired twin is shadowed only by its installed replacement`() {
        val present = setOf("com.reveng.clock", "com.ripostelabs.clock", "com.reveng.weather")
        assertEquals(listOf("com.reveng.clock"), RiposteSuite.retiredTwins(present))
    }

    @Test
    fun `retired twins are registry members only`() {
        val present = setOf("com.reveng.other", "com.ripostelabs.other", "com.reveng.carlauncher")
        assertTrue(RiposteSuite.retiredTwins(present).isEmpty())
    }

    @Test
    fun `liveTwin maps a retired package onto the rewrite and leaves the rest alone`() {
        assertEquals("com.ripostelabs.clock", RiposteSuite.liveTwin("com.reveng.clock"))
        assertEquals("com.ripostelabs.clock", RiposteSuite.liveTwin("com.ripostelabs.clock"))
        assertEquals("com.reveng.other", RiposteSuite.liveTwin("com.reveng.other"))
        assertEquals("com.android.chrome", RiposteSuite.liveTwin("com.android.chrome"))
    }

    /**
     * The registry is the suite as built (rav4-apps `apps/com.ripostelabs.*`). Lamp and
     * Projection joined the suite after this list was written and were missing from it for two
     * weeks: the Setup doctor read "26/26" with 28 apps installed and could never report either
     * absent. Pin the count and the two late members.
     */
    @Test
    fun registryMatchesTheSuiteAsBuilt() {
        assertEquals(28, RiposteSuite.APPS.size)
        assertTrue(RiposteSuite.isSuiteApp("com.ripostelabs.lamp"))
        assertTrue(RiposteSuite.isSuiteApp("com.ripostelabs.projection"))
        assertEquals(RiposteSuite.APPS.map { it.label }.sorted(), RiposteSuite.APPS.map { it.label })
    }
}
