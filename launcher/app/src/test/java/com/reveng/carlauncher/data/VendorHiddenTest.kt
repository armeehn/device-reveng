package com.reveng.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The vendor hidden-apps value (SysVar SYS_LAUNCHER_APP_HIDE_KEY) has an undocumented
 * separator, so the parser must accept every plausible packing and refuse garbage; the merge
 * must keep an app hidden when EITHER side hides it.
 */
class VendorHiddenTest {

    // ---- parseVendorHidden --------------------------------------------------

    @Test
    fun commaSemicolonAndPipeAllSplit() {
        val expected = setOf("com.a.one", "com.b.two", "com.c.three")
        assertEquals(expected, parseVendorHidden("com.a.one,com.b.two,com.c.three"))
        assertEquals(expected, parseVendorHidden("com.a.one;com.b.two;com.c.three"))
        assertEquals(expected, parseVendorHidden("com.a.one|com.b.two|com.c.three"))
    }

    @Test
    fun whitespaceAroundEntriesIsTrimmed() {
        assertEquals(
            setOf("com.a.one", "com.b.two"),
            parseVendorHidden(" com.a.one , com.b.two "),
        )
    }

    @Test
    fun nullEmptyAndSeparatorOnlyYieldNothing() {
        assertEquals(emptySet<String>(), parseVendorHidden(null))
        assertEquals(emptySet<String>(), parseVendorHidden(""))
        assertEquals(emptySet<String>(), parseVendorHidden(" ,;| "))
    }

    @Test
    fun tokensNotShapedLikeAPackageAreDropped() {
        // A defensive parse of an unconfirmed format: keep what is clearly a package name,
        // drop what clearly is not, fabricate nothing in between.
        assertEquals(
            setOf("com.ok.app", "com.also_ok.app2"),
            parseVendorHidden("com.ok.app,{junk!},com.also_ok.app2,=1"),
        )
    }

    @Test
    fun mixedSeparatorsInOneValueStillParse() {
        assertEquals(
            setOf("com.a.one", "com.b.two", "com.c.three"),
            parseVendorHidden("com.a.one;com.b.two|com.c.three"),
        )
    }

    // ---- mergedPlacement ----------------------------------------------------

    private val noLocal = emptyMap<String, Placement>()

    @Test
    fun vendorHiddenWinsOverEverything() {
        val vendor = setOf("com.x")
        assertEquals(Placement.HIDDEN, mergedPlacement("com.x", false, noLocal, vendor))
        assertEquals(Placement.HIDDEN, mergedPlacement("com.x", true, noLocal, vendor))
        // Even an explicit local HOME placement: the union of the hidden sets is hidden.
        assertEquals(
            Placement.HIDDEN,
            mergedPlacement("com.x", false, mapOf("com.x" to Placement.HOME), vendor),
        )
    }

    @Test
    fun withoutVendorEntryLocalRulesApply() {
        assertEquals(Placement.HOME, mergedPlacement("com.x", false, noLocal, emptySet()))
        assertEquals(Placement.SYSTEM, mergedPlacement("com.x", true, noLocal, emptySet()))
        assertEquals(
            Placement.HIDDEN,
            mergedPlacement("com.x", false, mapOf("com.x" to Placement.HIDDEN), emptySet()),
        )
    }

    @Test
    fun vendorListOnlyAffectsItsOwnPackages() {
        assertEquals(
            Placement.HOME,
            mergedPlacement("com.y", false, noLocal, setOf("com.x")),
        )
    }
}
