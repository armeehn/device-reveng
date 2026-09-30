package com.ripostelabs.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window's height per state: what the apps above it lose, so the bar reserves no more. */
class NavPanelTest {

    @Test
    fun expandedIsTheKeyStripAndHandleIsATouchEdge() {
        assertEquals(64, NavPanel.heightDp(ICarService.NAV_EXPANDED))
        assertEquals(24, NavPanel.heightDp(ICarService.NAV_HANDLE))
        assertEquals(0, NavPanel.heightDp(ICarService.NAV_HIDDEN))
    }

    // RAV4-101: the folded strip is a visible bar (36 px on the 240 dpi panel) carrying a
    // 240 x 12 px pill, centred with at least its own height of margin above and below.
    @Test
    fun foldedStripCarriesAPillWithRoomAroundIt() {
        val strip = NavPanel.heightDp(ICarService.NAV_HANDLE)

        assertEquals(160, NavPanel.PILL_WIDTH_DP)
        assertEquals(8, NavPanel.PILL_HEIGHT_DP)
        assertTrue(NavPanel.PILL_HEIGHT_DP * 2 <= strip)
    }
}
