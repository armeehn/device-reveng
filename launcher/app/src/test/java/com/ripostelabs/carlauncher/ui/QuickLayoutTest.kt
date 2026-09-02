package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.IntentSpec
import com.ripostelabs.carlauncher.carlib.Zlink
import org.junit.Assert.assertEquals
import org.junit.Test

/** RAV4-52: the quick-launch reflow while a phone is projected, and what each shortcut sends. */
class QuickLayoutTest {

    private val carPlay = "carplay"
    private val apps = listOf(carPlay, "claude", "phone", "fill1", "fill2", "fill3")
    private val columns = 3
    private val maxRows = 2

    private fun layout(projection: Projection, apps: List<String> = this.apps) =
        quickLayout(apps, columns, maxRows, projection) { it == carPlay }

    @Test
    fun idleIsRowMajorApps() {
        val expected = listOf(
            listOf(QuickSlot.App(carPlay), QuickSlot.App("claude"), QuickSlot.App("phone")),
            listOf(QuickSlot.App("fill1"), QuickSlot.App("fill2"), QuickSlot.App("fill3")),
        )
        assertEquals(expected, layout(Projection.IDLE).rows)
    }

    @Test
    fun projectedReplacesTheCarPlayTileWithTheShortcutRow() {
        val rows = layout(Projection.PROJECTED).rows

        assertEquals(CarPlayAction.entries.map { QuickSlot.Action(it) }, rows[0])
        // The two lowest fills drop; the grid keeps its two rows.
        assertEquals(
            listOf(QuickSlot.App("claude"), QuickSlot.App("phone"), QuickSlot.App("fill1")),
            rows[1],
        )
        assertEquals(maxRows, rows.size)
    }

    @Test
    fun shortcutOrderIsSiriMapsMusicNowPlayingHome() {
        assertEquals(
            listOf("Siri", "Maps", "Music", "Now playing", "Home"),
            CarPlayAction.entries.map { it.label },
        )
    }

    @Test
    fun projectedWithoutACarPlayTileChangesNothing() {
        val noCarPlay = apps.drop(1)
        assertEquals(layout(Projection.IDLE, noCarPlay), layout(Projection.PROJECTED, noCarPlay))
    }

    @Test
    fun focusIndexRunsRowMajorAcrossTheShortcutRow() {
        val slots = layout(Projection.PROJECTED).slots

        assertEquals(8, slots.size)
        assertEquals(QuickSlot.Action(CarPlayAction.HOME), slots[4])
        assertEquals(QuickSlot.App("claude"), slots[5])
    }

    @Test
    fun eachShortcutIsOneSpecialFunctionBroadcast() {
        val codes = mapOf(
            CarPlayAction.SIRI to 1500,
            CarPlayAction.MAPS to 1504,
            CarPlayAction.MUSIC to 1506,
            CarPlayAction.NOW_PLAYING to 1507,
            CarPlayAction.HOME to 1508,
        )
        for ((action, code) in codes) {
            assertEquals(
                IntentSpec(
                    action = "com.zjinnova.zlink",
                    strings = mapOf("command" to "REQ_SPEC_FUNC_CMD"),
                    ints = mapOf("specFuncCode" to code),
                ),
                action.intent(),
            )
        }
        assertEquals(codes.keys, CarPlayAction.entries.toSet())
    }

    @Test
    fun shortcutsNeverStartAnActivityOrNameAPackage() {
        // A broadcast with no target package, exactly as the gateway sends it
        // (ZlinkManage.java:608-613). An explicit package or activity would be a guess.
        for (action in CarPlayAction.entries) {
            assertEquals(null, action.intent().packageName)
            assertEquals(Zlink.ACTION_MESSAGE, action.intent().action)
        }
    }
}
