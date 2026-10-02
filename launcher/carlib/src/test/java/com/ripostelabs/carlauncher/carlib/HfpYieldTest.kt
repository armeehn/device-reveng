package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RAV4-278: a cellular call on a CarPlay phone also reaches the HF client. Telecom then makes it
 * a SIM call, puts the audio mode in IN_CALL, and the platform silences every other capture,
 * the CarPlay mic among them. CarPlay carries the call itself, so while it is up the car kit
 * parks the HF client for the phone and gives it back when CarPlay ends.
 */
class HfpYieldTest {

    private val phone = "AC:16:15:8A:A4:62"
    private val other = "00:11:22:33:44:55"

    /** The case from the car, 2026-10-01 08:24: CarPlay up, the phone's HFP reports a call. */
    @Test
    fun carPlayWithAnHfpCallStillParks() {
        assertEquals(HfpYield.Step.PARK, HfpYield.step(carPlay = true, hfDevice = phone, parked = emptySet()))
    }

    @Test
    fun aParkedPhoneStillDisconnectingIsLeftAlone() {
        assertEquals(HfpYield.Step.NONE, HfpYield.step(carPlay = true, hfDevice = phone, parked = setOf(phone)))
    }

    @Test
    fun aSecondPhoneDuringCarPlayIsParkedToo() {
        assertEquals(HfpYield.Step.PARK, HfpYield.step(carPlay = true, hfDevice = other, parked = setOf(phone)))
    }

    @Test
    fun carPlayEndingGivesTheLinkBack() {
        assertEquals(HfpYield.Step.RESTORE, HfpYield.step(carPlay = false, hfDevice = null, parked = setOf(phone)))
    }

    /** A park left from before a crash or reboot is undone at the first reading without CarPlay. */
    @Test
    fun aStaleParkIsUndoneWithoutCarPlay() {
        assertEquals(HfpYield.Step.RESTORE, HfpYield.step(carPlay = false, hfDevice = other, parked = setOf(phone)))
    }

    @Test
    fun noCarPlayAndNothingParkedLeavesBluetoothCallsAlone() {
        assertEquals(HfpYield.Step.NONE, HfpYield.step(carPlay = false, hfDevice = phone, parked = emptySet()))
    }

    @Test
    fun carPlayWithoutAHandsFreePhoneDoesNothing() {
        assertEquals(HfpYield.Step.NONE, HfpYield.step(carPlay = true, hfDevice = null, parked = emptySet()))
    }
}
