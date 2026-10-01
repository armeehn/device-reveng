package com.ripostelabs.carlauncher.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RAV4-273: a panel, wheel or box key that opens a launcher screen must show it from any app.
 * The farm pass pressed box HOME over Maps and nothing moved, because the key only switched
 * the launcher's own screen state behind Maps.
 */
class ScreenDoorTest {

    private var front = false
    private var shown: String? = null
    private var raised = 0

    private val door = ScreenDoor<String>(
        inFront = { front },
        show = { shown = it },
        toFront = { raised++ },
    )

    @Test
    fun keyOverAnotherAppRaisesLauncher() {
        front = false

        door.open("Home")

        assertEquals("Home", shown)
        assertEquals(1, raised)
    }

    @Test
    fun keyWithLauncherInFrontOnlySwitches() {
        front = true

        door.open("Media")

        assertEquals("Media", shown)
        assertEquals(0, raised)
    }
}
