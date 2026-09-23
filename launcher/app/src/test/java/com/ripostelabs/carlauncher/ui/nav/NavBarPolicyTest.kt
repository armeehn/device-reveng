package com.ripostelabs.carlauncher.ui.nav

import com.ripostelabs.carlauncher.data.NavBarMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar's visibility over a foreign app is a pure decision on three inputs: the mode the
 * driver picked, the package in front, and time or touch. The window code only renders what
 * [NavBarPolicy] answers, so the cases the car showed up (a 64 dp strip over wireless CarPlay,
 * a bar that never gets out of the way) are pinned here.
 */
class NavBarPolicyTest {

    private companion object {
        const val SELF = "com.ripostelabs.carlauncher"
        const val MAPS = "com.google.android.apps.maps"
    }

    @Test
    fun foreignAppInFrontExpandsTheBar() {
        val p = NavBarPolicy(NavBarMode.AUTO_HIDE, SELF)

        assertEquals(NavBarState.EXPANDED, p.onForeground(MAPS))
        assertTrue("an expanded bar in auto-hide arms the timer", p.armsTimer())
    }

    @Test
    fun projectionNeverGetsTheBar() {
        val p = NavBarPolicy(NavBarMode.AUTO_HIDE, SELF)
        p.onForeground(MAPS)

        assertEquals(NavBarState.HIDDEN, p.onForeground(NavBarPolicy.PROJECTION_PACKAGE))
        assertEquals("a touch cannot bring it back over CarPlay", NavBarState.HIDDEN, p.onInteract())
        assertFalse(p.armsTimer())
    }

    @Test
    fun alwaysShownStillYieldsToProjection() {
        val p = NavBarPolicy(NavBarMode.ALWAYS_SHOWN, SELF)

        assertEquals(NavBarState.HIDDEN, p.onForeground(NavBarPolicy.PROJECTION_PACKAGE))
    }

    @Test
    fun leavingProjectionBringsTheBarBack() {
        val p = NavBarPolicy(NavBarMode.AUTO_HIDE, SELF)
        p.onForeground(NavBarPolicy.PROJECTION_PACKAGE)

        assertEquals(NavBarState.EXPANDED, p.onForeground(MAPS))
    }

    @Test
    fun autoHideCollapsesOnTimeoutAndExpandsOnTouch() {
        val p = NavBarPolicy(NavBarMode.AUTO_HIDE, SELF)
        p.onForeground(MAPS)

        assertEquals(NavBarState.HANDLE, p.onTimeout())
        assertFalse("nothing to time out while collapsed", p.armsTimer())
        assertEquals("a later poll does not re-expand a collapsed bar", NavBarState.HANDLE, p.onForeground(MAPS))
        assertEquals(NavBarState.EXPANDED, p.onInteract())
        assertTrue(p.armsTimer())
        assertEquals(NavBarState.HANDLE, p.onTimeout())
    }

    @Test
    fun alwaysShownIgnoresTheTimer() {
        val p = NavBarPolicy(NavBarMode.ALWAYS_SHOWN, SELF)
        p.onForeground(MAPS)

        assertFalse(p.armsTimer())
        assertEquals(NavBarState.EXPANDED, p.onTimeout())
    }

    @Test
    fun offNeverShowsAnything() {
        val p = NavBarPolicy(NavBarMode.OFF, SELF)

        assertEquals(NavBarState.HIDDEN, p.onForeground(MAPS))
        assertEquals(NavBarState.HIDDEN, p.onInteract())
        assertFalse(p.armsTimer())
    }

    @Test
    fun ownPackageInFrontIsNotADecision() {
        // onPause fires before the next activity resumes, so the first read of the foreground
        // still names the launcher. Showing then would flash the bar over CarPlay for a poll.
        val p = NavBarPolicy(NavBarMode.AUTO_HIDE, SELF)

        assertEquals(NavBarState.HIDDEN, p.onForeground(SELF))
        assertEquals("keep asking quickly until something else is in front", NavBarPolicy.SETTLE_POLL_MS, p.nextPollMs())

        p.onForeground(MAPS)
        assertEquals(NavBarPolicy.FOREGROUND_POLL_MS, p.nextPollMs())
    }

    @Test
    fun unknownForegroundShowsTheBar() {
        // No root means no dumpsys; a driver without a way back is worse than a bar over CarPlay.
        val p = NavBarPolicy(NavBarMode.AUTO_HIDE, SELF)

        assertEquals(NavBarState.EXPANDED, p.onForeground(null))
    }

    @Test
    fun hidingResetsForTheNextApp() {
        val p = NavBarPolicy(NavBarMode.AUTO_HIDE, SELF)
        p.onForeground(NavBarPolicy.PROJECTION_PACKAGE)
        p.onHide()

        assertEquals(NavBarState.HIDDEN, p.state)
        assertEquals(NavBarState.EXPANDED, p.onForeground(MAPS))
    }
}
