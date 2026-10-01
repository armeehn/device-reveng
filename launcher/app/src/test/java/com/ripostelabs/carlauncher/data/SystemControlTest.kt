package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.AecPath
import com.ripostelabs.carlauncher.carlib.CarProfile
import com.ripostelabs.carlauncher.carlib.Hotspot
import com.ripostelabs.carlauncher.carlib.McuOwner
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import com.ripostelabs.carlauncher.carlib.McuPort
import com.ripostelabs.carlauncher.carlib.MicGain
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAV4-216: the hotspot and the language go through the car service, or nothing is sent. */
class SystemControlTest {

    /** The owner as the launcher sees it: a car service at 9, or an older one ([routed] false). */
    private class FakePort(private val routed: Boolean, var held: Hotspot? = Hotspot.OFF) : McuPort {
        val sent = mutableListOf<String>()
        override val status: StateFlow<McuOwner.Status> = MutableStateFlow(McuOwner.Status.Idle)
        override val lastMode: McuOwnerProtocol.Mode? = null
        override fun start() = Unit
        override fun stop() = Unit
        override fun release() = Unit
        override fun send(frame: ByteArray) = Unit
        override fun setMode(mode: McuOwnerProtocol.Mode) = false
        override fun selectCar(profile: CarProfile) = Unit
        override val controlsSystem: Boolean
            get() = routed
        override fun hotspot(): Hotspot? = if (routed) held else null
        override fun setHotspot(state: Hotspot): Boolean {
            if (!routed) {
                return false
            }
            sent += "hotspot $state"
            return true
        }
        override fun setLanguage(tag: String): Boolean {
            if (!routed) {
                return false
            }
            sent += "language $tag"
            return true
        }
        override val controlsCallAudio: Boolean
            get() = routed
        override fun setAecDelay(path: AecPath, ms: Int): Boolean {
            if (!routed) {
                return false
            }
            sent += "aec $path $ms"
            return true
        }
        override fun micGain(): MicGain? = if (routed) MicGain.G96 else null
        override fun setMicGain(gain: MicGain): Boolean {
            if (!routed) {
                return false
            }
            sent += "mic $gain"
            return true
        }
    }

    // RAV4-184: call audio goes through a service at 10; without one nothing is sent.
    @Test
    fun callAudioGoesOnlyThroughTheService() {
        val port = FakePort(routed = true)
        val control = SystemControl { port }
        assertTrue(control.callAudioRouted)
        assertEquals(MicGain.G96, control.micGain())
        assertTrue(control.setAecDelay(AecPath.PHONE, 120))
        assertTrue(control.setMicGain(MicGain.G112))
        assertEquals(listOf("aec PHONE 120", "mic G112"), port.sent)

        val old = FakePort(routed = false)
        val none = SystemControl { old }
        assertFalse(none.callAudioRouted)
        assertFalse(none.setMicGain(MicGain.G85))
        assertTrue(old.sent.isEmpty())
        assertFalse(SystemControl { null }.setAecDelay(AecPath.CARPLAY, 0))
    }

    @Test
    fun toggleFlipsWhatTheSystemHolds() {
        val port = FakePort(routed = true, held = Hotspot.OFF)
        val control = SystemControl { port }

        assertTrue(control.routed)
        assertTrue(control.toggleHotspot())
        port.held = Hotspot.ON
        assertTrue(control.toggleHotspot())

        assertEquals(listOf("hotspot ON", "hotspot OFF"), port.sent)
    }

    // An older service or none at all: nothing is sent, so the caller opens Android's page.
    @Test
    fun withoutTheServiceNothingIsSent() {
        val old = FakePort(routed = false)

        assertFalse(SystemControl { old }.routed)
        assertFalse(SystemControl { old }.toggleHotspot())
        assertFalse(SystemControl { old }.setLanguage("fr-CA"))
        assertFalse(SystemControl { null }.routed)
        assertFalse(SystemControl { null }.toggleHotspot())
        assertTrue(old.sent.isEmpty())
    }

    @Test
    fun languageReachesTheService() {
        val port = FakePort(routed = true)

        assertTrue(SystemControl { port }.setLanguage("fr-CA"))
        assertEquals(listOf("language fr-CA"), port.sent)
    }

    // The unit lists a language once per tag; empty and malformed tags are left out.
    @Test
    fun languagesAreOnePerTagByName() {
        val tags = arrayOf("fr-CA", "en-US", "fr-CA", "", "de")

        assertEquals(
            listOf("en-US" to "English (United States)", "fr-CA" to "French (Canada)", "de" to "German"),
            SystemControl.languages(tags, Locale.ENGLISH),
        )
    }
}
