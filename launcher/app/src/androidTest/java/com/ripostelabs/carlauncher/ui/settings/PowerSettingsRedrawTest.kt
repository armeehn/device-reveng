package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.SettingKeys
import com.ripostelabs.carlauncher.data.SysVarLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RAV4-214: on the owner path a Power & sleep pick wrote the row but the row kept its old label
 * until the screen was reopened. The option rows read the controller outside Compose state and
 * took unchanged parameters, so Compose skipped them when the snapshot moved.
 */
@RunWith(AndroidJUnit4::class)
class PowerSettingsRedrawTest {

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
    fun aPickShowsAtOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = CarSettingsController(
            context,
            scope,
            localStore = SysVarLocalStore(MemoryRows()),
            rootProbe = { false },
        )

        compose.setContent {
            PowerSettingsScreen(controller = controller, carEvents = CarEvents(context), onBack = {})
        }
        // Screensaver and screen-off both start at Never.
        compose.onAllNodesWithText("Never", useUnmergedTree = true).assertCountEquals(2)

        // What the picker's onSelect does: one row write through the controller.
        compose.runOnIdle { controller.setInt(SettingKeys.AUTO_SCREENSAVER_TIME, FIVE_MINUTES) }
        compose.waitUntil(WAIT_MS) { controller.getString(SettingKeys.AUTO_SCREENSAVER_TIME) == "$FIVE_MINUTES" }

        compose.onNodeWithText("5 min", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Never", useUnmergedTree = true).assertCountEquals(1)
    }

    private companion object {
        const val FIVE_MINUTES = 300
        const val WAIT_MS = 5_000L
    }
}
