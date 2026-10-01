package com.ripostelabs.carlauncher.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAV4-255: the Phone page's way into the Projection app's noise-reduction screen. */
class MicSettingsLinkTest {

    /** Records what the link asks Android for, and answers as told. */
    private class FakePort(private val installed: Boolean) : ActivityPort {
        val started = mutableListOf<IntentTarget>()

        override fun resolves(target: IntentTarget): Boolean = installed

        override fun start(target: IntentTarget): Boolean {
            started += target
            return installed
        }
    }

    @Test
    fun targetIsTheProjectionMicScreen() {
        // The exported MicSettingsActivity's filter in rav4-apps, action plus DEFAULT.
        val target = MicSettingsLink.TARGET

        assertEquals("com.ripostelabs.projection.MIC_SETTINGS", target.action)
        assertEquals("android.intent.category.DEFAULT", target.category)
    }

    @Test
    fun installedAppGivesAnEnabledRowThatOpensIt() {
        val port = FakePort(installed = true)
        val link = MicSettingsLink(port)

        val row = link.row()
        assertTrue(row.enabled)
        assertEquals("Noise reduction for Siri and CarPlay calls", row.subtitle)

        assertTrue(link.open())
        assertEquals(listOf(MicSettingsLink.TARGET), port.started)
    }

    @Test
    fun missingAppGivesADisabledRowThatStartsNothing() {
        val port = FakePort(installed = false)
        val link = MicSettingsLink(port)

        val row = link.row()
        assertFalse(row.enabled)
        assertEquals("Install the Projection app", row.subtitle)

        assertFalse(link.open())
        assertTrue(port.started.isEmpty())
    }

    @Test
    fun phonePageCarriesTheRowInCallAudio() {
        // Compose is not on the JVM test path, so the row's place is checked in the source:
        // the Microphone row sits in the "Call audio" section beside echo delay and mic gain.
        val source = File(PHONE_SCREEN).readText()
        val section = source.substringAfter("SettingsSection(title = \"Call audio\")")

        assertTrue("Microphone row missing from Call audio", "MicSettingsRow()" in section)
        assertEquals("Microphone", MicSettingsLink.LABEL)
    }

    private companion object {
        const val PHONE_SCREEN =
            "src/main/java/com/ripostelabs/carlauncher/ui/settings/PhoneSettingsScreen.kt"
    }
}
