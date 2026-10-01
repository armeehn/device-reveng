package com.ripostelabs.carlauncher.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAV4-200: which favourites the edge hotbar shows, and which gestures open it. */
class HotbarPolicyTest {

    private val self = "com.ripostelabs.carlauncher"

    @Test
    fun `slots keep installed favourites in label order`() {
        val labels = mapOf("com.b" to "Spotify", "com.a" to "maps", "com.c" to "Chrome")
        val slots = HotbarPolicy.slots(setOf("com.b", "com.a", "com.c"), labels, self, capacity = 8)
        assertEquals(listOf("com.c", "com.a", "com.b"), slots)
    }

    @Test
    fun `uninstalled favourites and the launcher itself are skipped`() {
        val labels = mapOf("com.a" to "Maps", self to "Car Launcher")
        val slots = HotbarPolicy.slots(setOf("com.a", "com.gone", self), labels, self, capacity = 8)
        assertEquals(listOf("com.a"), slots)
    }

    @Test
    fun `slots stop at the strip's capacity`() {
        val labels = (1..10).associate { "com.p$it" to "App %02d".format(it) }
        val slots = HotbarPolicy.slots(labels.keys, labels, self, capacity = 6)
        assertEquals(6, slots.size)
        assertEquals("com.p1", slots.first())
    }

    @Test
    fun `capacity fits whole slots above the nav bar`() {
        // 480 dp panel, 64 dp nav bar, 16 dp padding each end: 384 dp, 5 whole 72 dp slots.
        assertEquals(5, HotbarPolicy.capacity(panelDp = 480, navBarDp = 64, slotDp = 72, paddingDp = 16))
        assertEquals(0, HotbarPolicy.capacity(panelDp = 60, navBarDp = 64, slotDp = 72, paddingDp = 16))
    }

    @Test
    fun `a two-finger swipe to the right opens it`() {
        assertTrue(HotbarPolicy.swipeOpens(pointers = 2, dxDp = 80f))
        assertTrue(HotbarPolicy.swipeOpens(pointers = 3, dxDp = 120f))
    }

    @Test
    fun `one finger, a short swipe or a left swipe does not`() {
        assertFalse(HotbarPolicy.swipeOpens(pointers = 1, dxDp = 200f))
        assertFalse(HotbarPolicy.swipeOpens(pointers = 2, dxDp = 20f))
        assertFalse(HotbarPolicy.swipeOpens(pointers = 2, dxDp = -200f))
    }
}
