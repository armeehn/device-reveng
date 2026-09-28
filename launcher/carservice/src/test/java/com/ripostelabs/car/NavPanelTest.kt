package com.ripostelabs.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** The window's height per state: what the apps above it lose, so the bar reserves no more. */
class NavPanelTest {

    @Test
    fun expandedIsTheKeyStripAndHandleIsATouchEdge() {
        assertEquals(64, NavPanel.heightDp(ICarService.NAV_EXPANDED))
        assertEquals(12, NavPanel.heightDp(ICarService.NAV_HANDLE))
        assertEquals(0, NavPanel.heightDp(ICarService.NAV_HIDDEN))
    }
}
