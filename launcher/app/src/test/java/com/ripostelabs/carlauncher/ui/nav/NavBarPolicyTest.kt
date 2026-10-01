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

    /**
     * The unit, 2026-09-27: the strip was still up 12 s after an app launch in AUTO_HIDE. NavBar
     * rendered on every 1.5 s foreground poll, and each render restarted the 3 s fold timer, so
     * it never ran out. This plays NavBar's loop on a virtual clock: a render (re)arms the timer.
     */
    @Test
    fun pollsOverTheSameAppLetTheBarFold() {
        val p = NavBarPolicy(NavBarMode.AUTO_HIDE, SELF)
        var now = 0L
        var foldAt: Long? = null
        val render = { _: NavBarState -> foldAt = if (p.armsTimer()) now + NavBarPolicy.AUTO_HIDE_MS else null }

        p.onPoll(MAPS)?.let(render)
        while (now < 12_000L) {
            now += NavBarPolicy.FOREGROUND_POLL_MS
            if (foldAt?.let { it <= now } == true) {
                render(p.onTimeout())
            }
            p.onPoll(MAPS)?.let(render)
        }

        assertEquals(NavBarState.HANDLE, p.state)
    }

    @Test
    fun aPollAsksForARenderOnlyOnAChange() {
        val p = NavBarPolicy(NavBarMode.AUTO_HIDE, SELF)

        assertEquals(NavBarState.EXPANDED, p.onPoll(MAPS))
        assertEquals(null, p.onPoll(MAPS))
        assertEquals(NavBarState.HIDDEN, p.onPoll(NavBarPolicy.PROJECTION_PACKAGE))
    }
    /**
     * Audit 2026-09-30: the car service's bar is a navigation-bar panel, a system window above
     * every app overlay, so it sat on the bottom of the reverse picture over any foreign app.
     * While the picture is up the bar stays off; it comes back on the next poll after.
     */
    @Test
    fun reversePictureKeepsTheBarOff() {
        val p = NavBarPolicy(NavBarMode.ALWAYS_SHOWN, SELF)
        p.onForeground(MAPS)

        assertEquals(NavBarState.HIDDEN, p.onReverse(ReversePicture.UP))
        assertEquals("a poll cannot bring it back", null, p.onPoll(MAPS))
        assertEquals("nor a touch", NavBarState.HIDDEN, p.onInteract())

        p.onReverse(ReversePicture.DOWN)
        assertEquals(NavBarState.EXPANDED, p.onPoll(MAPS))
    }
}
