package com.ripostelabs.carlauncher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ROADMAP, stability invariant for every release from v3.1 to v4.0:
 *
 *   "the screenshot suite pins the four indicators on every screen that shows the bar,
 *    so a later redesign cannot silently drop them"
 *
 * This is that suite, and it deliberately is *not* a pixel diff. A screenshot comparison over a
 * UI with a live theme editor goes red on every legitimate colour change, gets muted, and then
 * pins nothing. These tests assert semantics instead: each chip carries a [StatusIndicatorTags]
 * identity, so a redesign may restyle a chip freely and only *losing* one turns the suite red.
 *
 * The other half of the invariant is the rule that makes the strip trustworthy: an indicator
 * with no backing source must VANISH, never show a fabricated value. So each chip is asserted
 * both ways — present when its source answers, gone when it does not. Making a chip
 * unconditional would turn every test here green and destroy the property they exist to hold.
 *
 * Sources are supplied directly to [StatusIndicatorsRow]. The emulator this runs on has no
 * root, no vendor EventService and no car, so half the real sources are absent there by
 * construction; feeding the state in is the only way to test the present case at all, and it
 * keeps the test independent of whatever the runner's Wi-Fi and Bluetooth happen to be doing.
 * [StatusBarIndicatorsTest] covers what the real, source-reading composable does on-device.
 *
 * Tags are read from the UNMERGED tree throughout: when the group is tappable it is
 * `clickable`, which merges its descendants' semantics and would otherwise swallow every
 * per-chip tag. `tappableGroupStillExposesEveryChip` pins that case on purpose.
 */
@RunWith(AndroidJUnit4::class)
class StatusIndicatorsTest {

    @get:Rule
    val compose = createComposeRule()

    private val wifiOnline = WifiStatus(enabled = true, connected = true, validated = true, bars = 3)
    private val btConnected = BtStatus(present = true, on = true, connectedCount = 1)
    private val volumeReadable = VolumeStatus(available = true, level = 12, muted = false)
    private val brightnessReadable = 60

    private fun setStrip(
        wifi: WifiStatus = wifiOnline,
        bt: BtStatus = btConnected,
        volume: VolumeStatus = volumeReadable,
        brightnessPercent: Int? = brightnessReadable,
        onOpen: (() -> Unit)? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                StatusIndicatorsRow(
                    wifi = wifi,
                    bt = bt,
                    volume = volume,
                    brightnessPercent = brightnessPercent,
                    onOpen = onOpen,
                )
            }
        }
    }

    private fun assertShown(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()

    private fun assertGone(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()

    // ---- all four, the invariant itself ------------------------------------

    @Test
    fun allFourIndicatorsRenderWhenEverySourceAnswers() {
        setStrip()

        assertShown(StatusIndicatorTags.GROUP)
        assertShown(StatusIndicatorTags.WIFI)
        assertShown(StatusIndicatorTags.BLUETOOTH)
        assertShown(StatusIndicatorTags.VOLUME)
        assertShown(StatusIndicatorTags.BRIGHTNESS)
    }

    /**
     * `clickable` sets mergeDescendants, which collapses the chips into the group node in the
     * merged tree. A test written against the merged tree would pass on the untappable strip
     * and then report all four chips missing the moment Quick Controls is wired up.
     */
    @Test
    fun tappableGroupStillExposesEveryChip() {
        setStrip(onOpen = {})

        assertShown(StatusIndicatorTags.WIFI)
        assertShown(StatusIndicatorTags.BLUETOOTH)
        assertShown(StatusIndicatorTags.VOLUME)
        assertShown(StatusIndicatorTags.BRIGHTNESS)
    }

    // ---- no source -> no chip ----------------------------------------------

    @Test
    fun bluetoothChipVanishesWithoutAnAdapter() {
        setStrip(bt = BtStatus(present = false, on = false, connectedCount = 0))

        assertGone(StatusIndicatorTags.BLUETOOTH)
        // The other three are unaffected: one dead source must not take the strip with it.
        assertShown(StatusIndicatorTags.WIFI)
        assertShown(StatusIndicatorTags.VOLUME)
        assertShown(StatusIndicatorTags.BRIGHTNESS)
    }

    @Test
    fun volumeChipVanishesWhenTheEventServiceIsUnbound() {
        setStrip(volume = VolumeStatus(available = false, level = 0, muted = false))

        assertGone(StatusIndicatorTags.VOLUME)
        assertShown(StatusIndicatorTags.WIFI)
        assertShown(StatusIndicatorTags.BLUETOOTH)
        assertShown(StatusIndicatorTags.BRIGHTNESS)
    }

    @Test
    fun brightnessChipVanishesWhenBrightnessIsUnreadable() {
        setStrip(brightnessPercent = null)

        assertGone(StatusIndicatorTags.BRIGHTNESS)
        assertShown(StatusIndicatorTags.WIFI)
        assertShown(StatusIndicatorTags.BLUETOOTH)
        assertShown(StatusIndicatorTags.VOLUME)
    }

    /**
     * Vanishing has to be silent. A leftover "Volume 0" or "Brightness 0%" would be exactly the
     * fabricated reading the rule forbids, and it would still be announced to a screen reader.
     */
    @Test
    fun absentSourcesLeaveNoFabricatedReading() {
        setStrip(
            volume = VolumeStatus(available = false, level = 0, muted = false),
            brightnessPercent = null,
        )

        compose.onAllNodesWithContentDescription("Volume", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Muted", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Brightness", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    // ---- a state is not a missing source -----------------------------------

    /**
     * Wi-Fi off is a *state*, not an absent source: the framework still answers, so the chip
     * stays and greys. Dropping it would leave the driver unable to tell "off" from "broken".
     */
    @Test
    fun wifiChipSurvivesTheRadioBeingOff() {
        setStrip(wifi = WifiStatus(enabled = false, connected = false, validated = false, bars = 0))

        assertShown(StatusIndicatorTags.WIFI)
    }

    /** Same for a muted head unit: muted is a reading, so the chip stays. */
    @Test
    fun volumeChipSurvivesMute() {
        setStrip(volume = VolumeStatus(available = true, level = 0, muted = true))

        assertShown(StatusIndicatorTags.VOLUME)
    }
}
