package com.ripostelabs.carlauncher

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ripostelabs.carlauncher.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back on Home must leave the launcher where it is. The launcher is the car's floor: when Back
 * fell through to the system, the task moved back and the panel showed the last app instead
 * (farm, 2026-09-22). A task sent back stops, so the lifecycle is the check: RESUMED or not.
 */
@RunWith(AndroidJUnit4::class)
class HomeBackTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun backOnHomeKeepsTheLauncher() {
        val settings = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)
        settings.setFirstRunComplete()
        Thread.sleep(FLAG_SETTLE_MS)   // the store writes on its own scope

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(HOME_WAIT_MS) {
                compose.onAllNodesWithText(HOME_MARKER).fetchSemanticsNodes().isNotEmpty()
            }

            // Unconditionally: plain pressBack throws when the app leaves, which is the bug.
            Espresso.pressBackUnconditionally()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    private companion object {
        const val FLAG_SETTLE_MS = 1_000L
        const val HOME_WAIT_MS = 20_000L
        const val HOME_MARKER = "Search apps"
    }
}
