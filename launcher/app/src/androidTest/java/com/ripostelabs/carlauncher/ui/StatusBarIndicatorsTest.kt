package com.ripostelabs.carlauncher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.ripostelabs.carlauncher.carlib.CarEvents
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end half of the indicator invariant: the real [StatusBar], with the real
 * source-reading [StatusIndicators] inside it, rendered on the emulator. [StatusIndicatorsTest]
 * proves the rendering rules; this proves the strip actually reaches the screen through the bar
 * every screen draws, rather than only through a composable a test calls directly.
 *
 * WHAT CAN HONESTLY BE ASSERTED HERE, AND WHAT CANNOT.
 * A CI emulator is not a head unit: no root, no vendor EventService, no car, no MCU backlight.
 * By the "no source -> no chip" rule that is *correct* behaviour, and a test demanding all four
 * chips would be a test demanding the launcher lie.
 *
 *   Wi-Fi      asserted present. ConnectivityManager/WifiManager exist on every Android
 *              image, so the source always answers and the chip is unconditional.
 *   volume     asserted ABSENT. It needs the vendor AIDL, which no emulator has; asserting the
 *              absence is the stronger test anyway — it is the rule, checked end to end.
 *   Bluetooth  not asserted either way. Whether an emulator image exposes a BluetoothAdapter
 *              varies by image and by runner, so either assertion would be a coin toss.
 *   brightness not asserted either way. Its presence is exactly what is in flux while the
 *              chip is made nullable, and pinning today's answer would fight that change.
 *
 * Those two are pinned by [StatusIndicatorsTest] instead, which supplies the sources.
 *
 * [StatusBar] is the single definition of the bar — every screen that shows one shows this
 * composable — so pinning it here pins it for all of them. Its two configurations differ only
 * in the parked-only lock, which dims the shelf icons and is the one thing that reflows the bar
 * at runtime; both are checked, because a reflow must not push the indicators out.
 */
@RunWith(AndroidJUnit4::class)
class StatusBarIndicatorsTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Unregistered on purpose: [CarEvents.register] would subscribe to vendor broadcasts that
     * never arrive here. Unregistered it simply serves its flows' defaults, which is what the
     * bar renders on a unit whose gateway has not answered yet.
     */
    private fun carEvents(): CarEvents =
        CarEvents(ApplicationProvider.getApplicationContext<Context>())

    private fun setBar(parkedLock: Boolean) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalParkedOnlyLock provides parkedLock) {
                    StatusBar(
                        carEvents = carEvents(),
                        // Left null: the emulator has neither, and that is the case under test.
                        carService = null,
                        settingsStore = null,
                        onOpenNotifications = {},
                        onOpenContinueWatching = {},
                    )
                }
            }
        }
    }

    @Test
    fun statusBarShowsTheIndicatorStrip() {
        setBar(parkedLock = false)

        compose.onNodeWithTag(StatusIndicatorTags.GROUP, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(StatusIndicatorTags.WIFI, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun indicatorStripSurvivesTheParkedOnlyLock() {
        setBar(parkedLock = true)

        compose.onNodeWithTag(StatusIndicatorTags.GROUP, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(StatusIndicatorTags.WIFI, useUnmergedTree = true).assertIsDisplayed()
    }

    /** The rule, end to end: no car on a runner, so no volume chip and no invented level. */
    @Test
    fun volumeChipIsAbsentWithoutTheVendorService() {
        setBar(parkedLock = false)

        compose.onNodeWithTag(StatusIndicatorTags.VOLUME, useUnmergedTree = true).assertDoesNotExist()
    }
}
