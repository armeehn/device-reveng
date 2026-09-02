package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.CarPlayState
import org.junit.Assert.assertEquals
import org.junit.Test

/** RAV4-52: the status-bar chip wording, from the `phoneMode` values the gateway stores. */
class CarPlayChipTest {

    @Test
    fun protocolAndLinkFromPhoneMode() {
        assertEquals("CarPlay wireless", carPlayChipText(CarPlayState(connected = true, phoneMode = "carplay_wireless")))
        assertEquals("CarPlay wired", carPlayChipText(CarPlayState(connected = true, phoneMode = "carplay_wired")))
        assertEquals("Android Auto wired", carPlayChipText(CarPlayState(connected = true, phoneMode = "auto_wired")))
        assertEquals("HiCar wireless", carPlayChipText(CarPlayState(connected = true, phoneMode = "hicar_wireless")))
        assertEquals("Mirror wired", carPlayChipText(CarPlayState(connected = true, phoneMode = "android_mirror_wired")))
    }

    @Test
    fun aCallReplacesTheLink() {
        val state = CarPlayState(connected = true, phoneMode = "carplay_wireless", inCall = true)
        assertEquals("CarPlay call", carPlayChipText(state))
    }

    @Test
    fun unknownModeIsJustCarPlay() {
        // MAIN_AUDIO_START alone (launcher started after the phone connected) names no mode.
        assertEquals("CarPlay", carPlayChipText(CarPlayState(connected = true)))
        assertEquals("CarPlay", carPlayChipText(CarPlayState(connected = true, phoneMode = "carplay")))
    }
}
