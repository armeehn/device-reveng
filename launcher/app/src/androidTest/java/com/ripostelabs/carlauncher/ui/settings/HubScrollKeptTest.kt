package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.RadioPresetsStore
import com.ripostelabs.carlauncher.data.RootTierController
import com.ripostelabs.carlauncher.data.SettingsStore
import com.ripostelabs.carlauncher.data.SysVarLocalStore
import com.ripostelabs.carlauncher.data.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back from a Settings page returns to the hub where the driver left it. The hub used to come
 * back at the top, so every visit to a low row meant scrolling the whole list again: slow for a
 * driver, and the reason the Maestro rows journey timed out on a slow CI emulator (2026-09-30).
 */
@RunWith(AndroidJUnit4::class)
class HubScrollKeptTest {

    @get:Rule
    val compose = createComposeRule()

    /** The owner path's rows, in memory: no vendor provider, as on Riposte OS 0.2. */
    private class MemoryRows : SysVarLocalStore.Rows {
        val map = HashMap<String, String>()
        override fun readAll(): Map<String, String> = HashMap(map)
        override fun write(key: String, value: String): Boolean {
            map[key] = value
            return true
        }
    }

    @Test
    fun backKeepsTheHubScrolled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val carService = CarService(context)
        val controller = CarSettingsController(
            context,
            scope,
            localStore = SysVarLocalStore(MemoryRows()),
            rootProbe = { false },
        )

        compose.setContent {
            SettingsHost(
                settingsStore = SettingsStore(context),
                controller = controller,
                carService = carService,
                carEvents = CarEvents(context),
                radioPresetsStore = RadioPresetsStore(context, scope),
                rootTier = RootTierController(context, scope, carService),
                updater = UpdateController(context, scope),
                onExit = {},
            )
        }

        // The last row sits several screens below the top of the hub.
        compose.onNodeWithText(LAST_ROW).performScrollTo().performClick()
        compose.waitUntil(WAIT_MS) { hasText(EXPORT_ACTION) }

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(WAIT_MS) { !hasText(EXPORT_ACTION) }

        compose.onNodeWithText(LAST_ROW).assertIsDisplayed()
    }

    private fun hasText(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val LAST_ROW = "SysVar export"
        const val EXPORT_ACTION = "Export snapshot"
        const val WAIT_MS = 5_000L
    }
}
