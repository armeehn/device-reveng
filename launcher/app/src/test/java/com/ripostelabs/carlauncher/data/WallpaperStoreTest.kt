package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Per-theme wallpaper naming, fallback and decode sizing. */
class WallpaperStoreTest {

    @Test
    fun `theme ids become safe file names`() {
        assertEquals("user.1727_x", WallpaperStore.safeName("user.1727/x"))
        assertEquals("builtin.riposte", WallpaperStore.safeName("builtin.riposte"))
        assertEquals("a_b", WallpaperStore.safeName("a b"))
    }

    @Test
    fun `each phase uses its own image`() {
        assertEquals(Phase.DAY, WallpaperStore.pick(Phase.DAY, hasDay = true, hasNight = true))
        assertEquals(Phase.NIGHT, WallpaperStore.pick(Phase.NIGHT, hasDay = true, hasNight = true))
    }

    @Test
    fun `a missing phase borrows the other image`() {
        assertEquals(Phase.DAY, WallpaperStore.pick(Phase.NIGHT, hasDay = true, hasNight = false))
        assertEquals(Phase.NIGHT, WallpaperStore.pick(Phase.DAY, hasDay = false, hasNight = true))
    }

    @Test
    fun `no image means no wallpaper`() {
        assertNull(WallpaperStore.pick(Phase.DAY, hasDay = false, hasNight = false))
    }

    @Test
    fun `decode halves until the image still covers the panel`() {
        // A 12 MP phone photo onto the 1920x720 panel: 4000/2 = 2000 >= 1920, 4000/4 < 1920.
        assertEquals(2, WallpaperStore.sampleSize(4000, 3000, 1920, 720))
        assertEquals(1, WallpaperStore.sampleSize(1920, 720, 1920, 720))
        assertEquals(1, WallpaperStore.sampleSize(800, 600, 1920, 720))
        assertEquals(4, WallpaperStore.sampleSize(8000, 6000, 1920, 720))
    }
}
