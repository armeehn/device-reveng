package com.ripostelabs.carlauncher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things must never happen: HOME being forwarded (the driver loses the way out of a game
 * with no touchscreen reach), and a press being forwarded to something that is not a game (the
 * vendor's reverse window, a phone call). Both are pinned here; the mapping is the easy part.
 */
class WheelGamepadTest {

    @Test
    fun `directions become the d-pad`() {
        assertEquals(WheelGamepad.KEYCODE_DPAD_UP, WheelGamepad.keyCodeFor(NavKey.UP))
        assertEquals(WheelGamepad.KEYCODE_DPAD_DOWN, WheelGamepad.keyCodeFor(NavKey.DOWN))
        assertEquals(WheelGamepad.KEYCODE_DPAD_LEFT, WheelGamepad.keyCodeFor(NavKey.LEFT))
        assertEquals(WheelGamepad.KEYCODE_DPAD_RIGHT, WheelGamepad.keyCodeFor(NavKey.RIGHT))
    }

    @Test
    fun `seek keys are left and right too`() {
        assertEquals(WheelGamepad.KEYCODE_DPAD_LEFT, WheelGamepad.keyCodeFor(NavKey.MEDIA_PREV))
        assertEquals(WheelGamepad.KEYCODE_DPAD_RIGHT, WheelGamepad.keyCodeFor(NavKey.MEDIA_NEXT))
    }

    @Test
    fun `confirm, back and menu`() {
        assertEquals(WheelGamepad.KEYCODE_BUTTON_A, WheelGamepad.keyCodeFor(NavKey.CENTER))
        assertEquals(WheelGamepad.KEYCODE_BUTTON_B, WheelGamepad.keyCodeFor(NavKey.BACK))
        assertEquals(WheelGamepad.KEYCODE_BUTTON_START, WheelGamepad.keyCodeFor(NavKey.MEDIA_PLAY_PAUSE))
    }

    @Test
    fun `a known frontend in the foreground is a game`() {
        assertTrue(WheelGamepad.isGame("com.retroarch.aarch64"))
        assertTrue(WheelGamepad.isGame("org.ppsspp.ppsspp"))
    }

    @Test
    fun `the package is read out of a real dumpsys line`() {
        // Verbatim shape from the emulator farm, 2026-09-08, with RetroArch in front.
        val line = "    topResumedActivity=ActivityRecord{6a0f8c2 u0 com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture t12}"

        assertEquals("com.retroarch.aarch64", WheelGamepad.packageFromTopResumed(line))
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `HOME is never forwarded`() {
        // The one press that must always return to the launcher, whatever is in front.
        assertNull(WheelGamepad.keyCodeFor(NavKey.HOME))
    }

    @Test
    fun `the source keys keep opening our screens`() {
        assertNull(WheelGamepad.keyCodeFor(NavKey.OPEN_MEDIA))
        assertNull(WheelGamepad.keyCodeFor(NavKey.OPEN_RADIO))
        assertNull(WheelGamepad.keyCodeFor(NavKey.OPEN_PHONE))
    }

    @Test
    fun `the launcher itself is not a game`() {
        assertFalse(WheelGamepad.isGame("com.ripostelabs.carlauncher"))
        assertFalse(WheelGamepad.isGame("com.ripostelabs.carlauncher.debug"))
    }

    @Test
    fun `the vendor reverse window and a phone call are not games`() {
        // Forwarding A into either is exactly the wrong moment to be pressing A.
        assertFalse(WheelGamepad.isGame("com.szchoiceway.eventcenter"))
        assertFalse(WheelGamepad.isGame("com.szchoiceway.btsuite"))
    }

    @Test
    fun `nothing in the foreground is not a game`() {
        assertFalse(WheelGamepad.isGame(null))
        assertFalse(WheelGamepad.isGame(""))
    }

    @Test
    fun `a package that merely contains a frontend id is not one`() {
        assertFalse(WheelGamepad.isGame("com.retroarch.aarch64.clone"))
    }

    @Test
    fun `a line without a record yields nothing`() {
        assertNull(WheelGamepad.packageFromTopResumed(null))
        assertNull(WheelGamepad.packageFromTopResumed("  mResumedActivity: null"))
        assertNull(WheelGamepad.packageFromTopResumed(""))
    }

    @Test
    fun `the launcher line parses to the launcher, which is then not a game`() {
        val line = "    topResumedActivity=ActivityRecord{1b2c3d u0 com.ripostelabs.carlauncher/.MainActivity t5}"

        val pkg = WheelGamepad.packageFromTopResumed(line)
        assertEquals("com.ripostelabs.carlauncher", pkg)
        assertFalse(WheelGamepad.isGame(pkg))
    }
}
