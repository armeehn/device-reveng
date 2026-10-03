package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.ui.ReverseCameraGate.Verdict
import com.ripostelabs.carlauncher.ui.ReverseCameraWindow.Layout
import com.ripostelabs.carlauncher.ui.ReverseCameraWindow.Warmth
import org.junit.Assert.assertEquals
import org.junit.Test

/** The reverse window kept open, unseen, while ACC is on, so a reverse finds the feed running. */
class ReverseCameraWindowPlanTest {

    @Test
    fun warmWhileAccIsOnAndTheCameraIsOurs() {
        assertEquals(Warmth.WARM, Warmth.of(accOn = true, ownerActive = true, permissionGranted = true, surroundShown = false))
    }

    // ACC off is standby (the camera gates close) or the unit losing power: let the camera go.
    @Test
    fun coldWithoutAccOwnerOrPermission() {
        assertEquals(Warmth.COLD, Warmth.of(accOn = false, ownerActive = true, permissionGranted = true, surroundShown = false))
        assertEquals(Warmth.COLD, Warmth.of(accOn = true, ownerActive = false, permissionGranted = true, surroundShown = false))
        assertEquals(Warmth.COLD, Warmth.of(accOn = true, ownerActive = true, permissionGranted = false, surroundShown = false))
    }

    // One AIS client per process: a hidden reverse stream would be closed under it by the 360 view.
    @Test
    fun coldWhileThe360ViewHoldsTheCamera() {
        assertEquals(Warmth.COLD, Warmth.of(accOn = true, ownerActive = true, permissionGranted = true, surroundShown = true))
    }

    @Test
    fun aPictureIsAlwaysTheFullWindow() {
        assertEquals(Layout.FULL, ReverseCameraWindow.plan(Verdict.PREVIEW, Warmth.COLD))
        assertEquals(Layout.FULL, ReverseCameraWindow.plan(Verdict.PREVIEW, Warmth.WARM))
        assertEquals(Layout.FULL, ReverseCameraWindow.plan(Verdict.NO_PERMISSION, Warmth.COLD))
    }

    // Out of reverse the feed keeps running in an invisible window instead of closing.
    @Test
    fun noPictureKeepsTheFeedWhileWarm() {
        assertEquals(Layout.WARM, ReverseCameraWindow.plan(Verdict.HIDDEN, Warmth.WARM))
        assertEquals(Layout.NONE, ReverseCameraWindow.plan(Verdict.HIDDEN, Warmth.COLD))
    }
}
