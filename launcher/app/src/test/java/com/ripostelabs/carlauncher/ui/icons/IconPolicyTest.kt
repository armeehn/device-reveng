package com.ripostelabs.carlauncher.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The routing and the cache key are the two places a themed icon goes quietly wrong: a CarPlay
 * tile restyled into a letter, or a key that misses on every frame and re-rasterises the drawer.
 */
class IconPolicyTest {

    private val riposteDay = IconLook(
        glyph = 0xFF1D1A17.toInt(),
        letter = 0xFFD81150.toInt(),
        plate = 0xFFEAE4D6.toInt(),
        border = 0xFF1D1A17.toInt(),
        cornerScale = 0f,
        hardEdge = true,
        monoType = true,
    )

    @Test
    fun suiteAppWithMonoLayerIsTinted() {
        assertEquals(IconSource.MONOCHROME, IconPolicy.source("com.ripostelabs.clock", MonoLayer.PRESENT))
    }

    @Test
    fun appWithoutMonoLayerGetsALetter() {
        assertEquals(IconSource.LETTER, IconPolicy.source("org.videolan.vlc", MonoLayer.ABSENT))
    }

    @Test
    fun carPlayKeepsItsRealIconEvenWithAMonoLayer() {
        assertEquals(IconSource.REAL, IconPolicy.source("com.zjinnova.zlink", MonoLayer.PRESENT))
        assertEquals(IconSource.REAL, IconPolicy.source("com.google.android.projection.gearhead", MonoLayer.ABSENT))
    }

    @Test
    fun vendorPackagesKeepTheirRealIcon() {
        assertEquals(IconSource.REAL, IconPolicy.source("com.szchoiceway.eventcenter", MonoLayer.ABSENT))
        assertEquals(IconSource.REAL, IconPolicy.source("com.syu.radio", MonoLayer.ABSENT))
    }

    @Test
    fun letterIsTheFirstLetterOrDigitUpperCased() {
        assertEquals("D", IconPolicy.letterFor("Device Info"))
        assertEquals("V", IconPolicy.letterFor("vlc"))
        assertEquals("7", IconPolicy.letterFor("7-Zip"))
        assertEquals("É", IconPolicy.letterFor(" éclair"))
    }

    @Test
    fun labelWithNoLetterFallsBackToHash() {
        assertEquals(IconPolicy.NO_LETTER, IconPolicy.letterFor(""))
        assertEquals(IconPolicy.NO_LETTER, IconPolicy.letterFor("..."))
    }

    @Test
    fun cacheKeyIsStableForTheSameInputs() {
        val a = IconKey("com.ripostelabs.clock/.MainActivity", 108, riposteDay)
        val b = IconKey("com.ripostelabs.clock/.MainActivity", 108, riposteDay.copy())

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun cacheKeySeparatesNightSizeAndComponent() {
        val day = IconKey("com.ripostelabs.clock/.MainActivity", 108, riposteDay)
        val night = day.copy(look = riposteDay.copy(glyph = 0xFFCFC7B8.toInt()))
        val small = day.copy(sizePx = 54)
        val other = day.copy(component = "com.ripostelabs.radio/.MainActivity")

        assertNotEquals(day, night)
        assertNotEquals(day, small)
        assertNotEquals(day, other)
    }
}
