package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.data.OemApps.OemClass
import com.ripostelabs.carlauncher.data.OemApps.ShadowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer hides OEM apps by these rules alone, so the rules are pinned here. The one that
 * must never regress: an OEM app whose replacement is missing stays visible, or a unit with
 * no suite installed loses its radio.
 */
class OemAppsTest {

    private val allOem = OemApps.APPS.mapTo(mutableSetOf()) { it.packageName }
    private val keep = OemApps.APPS.filter { it.oemClass == OemClass.KEEP }.map { it.packageName }
    private val remove = OemApps.APPS.filter { it.oemClass == OemClass.REMOVE }.map { it.packageName }
    private val everything = ShadowPolicy(hideReplaced = true, hideOemSettings = true)

    @Test
    fun `the matrix carries every package the task named`() {
        val expected = setOf(
            "com.szchoiceway.photoreader", "com.szchoiceway.apkinstall", "com.choiceway.weather",
            "com.mmbox.xbrowser", "com.android.atslcarconsole",
            "com.szchoiceway.radio", "com.szchoiceway.musicplayer", "com.szchoiceway.videoplayer",
            "com.szchoiceway.gps", "com.szchoiceway.settings", "com.szchoiceway.navigation",
            "com.szchoiceway.eventcenter", "com.szchoiceway.canbus2", "com.szchoiceway.customerui",
            "com.szchoiceway.btsuite", "com.zjinnova.zlink", "com.szchoiceway.learn.key",
            "com.szchoiceway.canbusdebug", "com.lfg.szchoiceway.canupgrade", "com.szchoiceway.zxwmedia",
        )
        assertEquals(expected, allOem)
        assertEquals(OemApps.APPS.size, allOem.size)
    }

    @Test
    fun `REMOVE hides unconditionally, KEEP never`() {
        val off = ShadowPolicy(hideReplaced = false, hideOemSettings = false)
        val hidden = OemApps.shadowed(allOem, off)

        assertEquals(remove.toSet(), hidden)
        assertTrue(keep.none { it in OemApps.shadowed(allOem, everything) })
    }

    @Test
    fun `a missing replacement keeps the OEM app visible`() {
        // No suite at all: only the REMOVE class goes, the radio and players stay.
        assertEquals(remove.toSet(), OemApps.shadowed(allOem, ShadowPolicy.DEFAULT))

        val withMusic = allOem + "com.ripostelabs.music"
        val hidden = OemApps.shadowed(withMusic, ShadowPolicy.DEFAULT)
        assertTrue("com.szchoiceway.musicplayer" in hidden)
        assertFalse("com.szchoiceway.videoplayer" in hidden)
    }

    @Test
    fun `the OEM radio needs the suite radio, not only the launcher screen`() {
        assertFalse("com.szchoiceway.radio" in OemApps.shadowed(allOem, ShadowPolicy.DEFAULT))
        val withRadio = allOem + "com.ripostelabs.radio"
        assertTrue("com.szchoiceway.radio" in OemApps.shadowed(withRadio, ShadowPolicy.DEFAULT))
    }

    @Test
    fun `the main toggle un-shadows the REPLACED class only`() {
        val suite = allOem + setOf(
            "com.ripostelabs.radio", "com.ripostelabs.music", "com.ripostelabs.video", "com.ripostelabs.gps",
        )
        val off = ShadowPolicy(hideReplaced = false, hideOemSettings = false)

        assertEquals(remove.toSet(), OemApps.shadowed(suite, off))
        val on = OemApps.shadowed(suite, ShadowPolicy.DEFAULT)
        assertEquals(
            remove.toSet() + setOf(
                "com.szchoiceway.radio", "com.szchoiceway.musicplayer",
                "com.szchoiceway.videoplayer", "com.szchoiceway.gps",
            ),
            on,
        )
    }

    @Test
    fun `OEM settings hides only on its own opt-in`() {
        assertFalse("com.szchoiceway.settings" in OemApps.shadowed(allOem, ShadowPolicy.DEFAULT))
        assertTrue("com.szchoiceway.settings" in OemApps.shadowed(allOem, everything))
        // The main toggle has no say over it, in either direction.
        val settingsOnly = ShadowPolicy(hideReplaced = false, hideOemSettings = true)
        assertTrue("com.szchoiceway.settings" in OemApps.shadowed(allOem, settingsOnly))
    }

    @Test
    fun `the camera viewer is never hidden until it has a replacement`() {
        assertFalse("com.szchoiceway.navigation" in OemApps.shadowed(allOem, everything))
        assertEquals(
            "no replacement yet",
            OemApps.pendingReason(OemApps.byPackage("com.szchoiceway.navigation")!!, allOem),
        )
    }

    @Test
    fun `shadowed never names a package that is not installed`() {
        assertTrue(OemApps.shadowed(emptySet(), everything).isEmpty())
    }

    @Test
    fun `the default policy matches the settings defaults`() {
        assertEquals(ShadowPolicy.DEFAULT, ShadowPolicy.from(LauncherSettings()))
        assertTrue(ShadowPolicy.DEFAULT.hideReplaced)
        assertFalse(ShadowPolicy.DEFAULT.hideOemSettings)
    }

    @Test
    fun `the report separates hidden, pending, removable and missing`() {
        val installed = allOem - "com.szchoiceway.btsuite" + "com.ripostelabs.music"
        val enabled = installed - "com.szchoiceway.canbus2"
        val report = OemApps.report(installed, enabled, ShadowPolicy.DEFAULT)

        assertEquals(
            remove.toSet() + "com.szchoiceway.musicplayer",
            report.shadowed.map { it.packageName }.toSet(),
        )
        assertEquals(
            setOf(
                "com.szchoiceway.radio", "com.szchoiceway.videoplayer", "com.szchoiceway.gps",
                "com.szchoiceway.settings", "com.szchoiceway.navigation",
            ),
            report.pending.map { it.packageName }.toSet(),
        )
        assertEquals(remove.toSet(), report.removable.map { it.packageName }.toSet())
        assertEquals(
            setOf("com.szchoiceway.btsuite", "com.szchoiceway.canbus2"),
            report.missingKeep.map { it.packageName }.toSet(),
        )
        assertEquals(remove.map { "pm uninstall -k --user 0 $it" }, report.uninstallLines)
    }

    @Test
    fun `pending reasons name what is missing`() {
        val video = OemApps.byPackage("com.szchoiceway.videoplayer")!!
        assertEquals("needs com.ripostelabs.video", OemApps.pendingReason(video, allOem))

        val settings = OemApps.byPackage("com.szchoiceway.settings")!!
        assertTrue(OemApps.pendingReason(settings, allOem).contains("Hide OEM System settings"))

        val radio = OemApps.byPackage("com.szchoiceway.radio")!!
        assertEquals("needs com.ripostelabs.radio", OemApps.pendingReason(radio, allOem))
        val reason = OemApps.pendingReason(radio, allOem + "com.ripostelabs.radio")
        assertTrue(reason.contains("Hide replaced OEM apps"))
    }
}
