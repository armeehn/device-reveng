package com.ripostelabs.carlauncher.a11y

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult
import com.google.android.apps.common.testing.accessibility.framework.Parameters
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage
import com.ripostelabs.carlauncher.MainActivity
import com.ripostelabs.carlauncher.data.SettingsStore
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Accessibility Test Framework's full preset over every screen a driver reaches from the
 * top bar: touch-target size, text contrast, missing labels, duplicate descriptions, clickable
 * spans. The launcher is a car UI: an unlabelled icon is read by nobody, a low-contrast label
 * is unreadable in sun, a small target is missed on a bumpy road.
 *
 *     MainActivity ──top-bar click──▶ screen ──AccessibilityHierarchyAndroid──▶ ATF checks
 *
 * Every finding goes to logcat (tag A11yAudit) and into the failure message, so the CI
 * report carries the list. ERRORs fail; WARNINGs are listed.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityAuditTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val screens = listOf(
        "Home" to null,
        "Power & sleep" to "Power & sleep",
        "Notifications" to "Notifications",
        "Continue watching" to "Continue watching",
        "Themes" to "Themes",
        "Quick controls" to "Quick controls",
        "Vehicle dashboard" to "Vehicle dashboard",
        "Driver profiles" to "Driver profiles",
        "Phone" to "Phone",
        "Settings" to "Settings",
    )

    @Before
    fun skipOnboarding() {
        SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext).setFirstRunComplete()
    }

    @Test
    fun everyTopBarScreenPassesTheAccessibilityPreset() {
        waitForHome()
        val report = StringBuilder()
        var errors = 0
        for ((name, description) in screens) {
            if (description != null) {
                rule.onAllNodesWithContentDescription(description)[0].performClick()
                rule.waitForIdle()
            }
            val results = audit()
            for (r in results) {
                val line = "$name: ${r.type} ${r.sourceCheckClass.simpleName} ${r.getMessage(java.util.Locale.ENGLISH)}" +
                    " @${r.element?.boundsInScreen}"
                Log.i(TAG, line)
                report.append(line).append('\n')
                if (r.type == AccessibilityCheckResultType.ERROR) errors++
            }
            if (description != null) {
                backToHome()
            }
        }
        Log.i(TAG, "$errors errors, ${report.lines().count { it.isNotBlank() }} findings over ${screens.size} screens")
        assertTrue("$errors accessibility errors:\n$report", errors == 0)
    }

    private fun audit(): List<AccessibilityHierarchyCheckResult> {
        // The View tree of a Compose screen is one AndroidComposeView; the semantics live in
        // the accessibility node tree, which UiAutomation hands over with Compose's nodes in.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // The automation connects on first use; the active window shows up a moment later.
        var root = instrumentation.uiAutomation.rootInActiveWindow
        var tries = 0
        while (root == null && tries++ < ROOT_TRIES) {
            Thread.sleep(ROOT_WAIT_MS)
            root = instrumentation.uiAutomation.rootInActiveWindow
        }
        root ?: throw AssertionError("no active window for the audit")
        val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root, instrumentation.targetContext).build()
        // The contrast checks read pixels: without a capture they answer nothing.
        val parameters = Parameters()
        instrumentation.uiAutomation.takeScreenshot()?.let { parameters.putScreenCapture(BitmapImage(it)) }
        val results = ArrayList<AccessibilityHierarchyCheckResult>()
        for (check in AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)) {
            results.addAll(check.runCheckOnHierarchy(hierarchy, null, parameters))
        }
        return results.filter { it.type == AccessibilityCheckResultType.ERROR || it.type == AccessibilityCheckResultType.WARNING }
    }

    /**
     * Home composes late on a cold CI emulator (services, first-run gate): a node query before
     * setContent throws "No compose hierarchies found", so the audit waits for the search field.
     */
    private fun waitForHome() {
        rule.waitUntil(HOME_WAIT_MS) {
            runCatching { rule.onAllNodesWithText("Search apps").fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
    }

    /** Back until Home shows its search field; one screen needs two (a finding of its own). */
    private fun backToHome() {
        repeat(3) {
            if (rule.onAllNodesWithText("Search apps").fetchSemanticsNodes().isNotEmpty()) return
            Espresso.pressBack()
            rule.waitForIdle()
        }
    }

    private companion object {
        const val TAG = "A11yAudit"
        const val HOME_WAIT_MS = 60_000L
        const val ROOT_TRIES = 20
        const val ROOT_WAIT_MS = 250L
    }
}
