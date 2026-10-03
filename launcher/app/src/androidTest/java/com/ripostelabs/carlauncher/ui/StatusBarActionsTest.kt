package com.ripostelabs.carlauncher.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ripostelabs.carlauncher.carlib.CarEvents
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RAV4-151 regression: on the car (vc983) Phone and Settings vanished from the top bar.
 *
 * The bar is one unweighted Row, so it measures left to right and the last children get
 * whatever width is left. The car shows more status chips than the farm (volume, outside air,
 * USB, CarPlay, a call chip) in a wider theme font, so the chips used it all up and the two
 * rightmost icons were measured at zero width. A narrower bar stands in for those extra chips:
 * the emulator has no source for them.
 *
 *   | clock | chips ......... | power ... profile | phone | settings |  <- 1280 dp, fits
 *   | clock | chips ......... | power ... profile |                     <- short: gone
 */
@RunWith(AndroidJUnit4::class)
class StatusBarActionsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun phoneAndSettingsSurviveAShortBar() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(SHORT_BAR_DP.dp)) {
                    StatusBar(
                        carEvents = CarEvents(ApplicationProvider.getApplicationContext<Context>()),
                        onOpenNotifications = {},
                        onOpenContinueWatching = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Phone").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    private companion object {
        /** Room for the clock and every icon, not for the chips as well. */
        const val SHORT_BAR_DP = 900
    }
}
