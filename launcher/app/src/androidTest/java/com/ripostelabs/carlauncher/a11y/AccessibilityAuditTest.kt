package com.ripostelabs.carlauncher.a11y

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.ripostelabs.carlauncher.data.DayNightMode
import com.ripostelabs.carlauncher.data.SettingsStore
import org.junit.Assert.assertTrue
import org.junit.After
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
 *                 └─Settings──▶ hub ──scroll+click──▶ 25 settings screens ──▶ ATF checks
 *
 * Every finding goes to logcat (tag A11yAudit) and into the failure message, so the CI
 * report carries the list. ERRORs fail; WARNINGs are listed.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityAuditTest {

    // Empty rule + a scenario launched after the first-run flag is written: a rule that owns
    // the activity launches it before @Before, and a fresh CI emulator then shows onboarding.
    @get:Rule
    val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity

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

    // Every row of SettingsHub, in the order the hub lists them. A row title is also the
    // title of the screen it opens, so the walk is: scroll the row into view, tap it, audit
    // what appears, Back to the hub.
    private val settingsRows = listOf(
        "Launcher",
        "App directory",
        "Display & Illumination",
        "Reverse camera",
        "Parking radar",
        "Audio & EQ",
        "Climate",
        "Radio",
        "Steering wheel",
        "Wheel gestures",
        "Power & sleep",
        "Root tier",
        "Setup doctor",
        "Backup & restore",
        "Updates",
        "System & about",
        "Vehicle",
        "Accessories",
        "Games",
        "Guided car tests",
        "CAN frame capture",
        "Radio info capture",
        "Vehicle data capture",
        "All settings (advanced)",
        "SysVar export",
    )

    @Before
    fun skipOnboardingAndLaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        // A fresh install asks for location, then camera, on first draw; each dialog covers
        // Home. Every dangerous permission the manifest requests is granted up front.
        val pm = instrumentation.targetContext.packageManager
        val requested = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        for (permission in requested) {
            val dangerous = runCatching {
                pm.getPermissionInfo(permission, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
            if (dangerous) {
                runCatching { instrumentation.uiAutomation.grantRuntimePermission(pkg, permission) }
            }
        }
        val settings = SettingsStore(instrumentation.targetContext)
        settings.setFirstRunComplete()
        // Day, always: the contrast figures depend on the palette, and a run that inherits
        // whatever mode the last one left is not comparable with the one before it.
        settings.setDayNightMode(DayNightMode.FORCE_DAY)
        Thread.sleep(FLAG_SETTLE_MS)   // the store writes on its own scope
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After
    fun close() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun everyTopBarScreenPassesTheAccessibilityPreset() {
        waitForHome()
        val report = AuditReport()
        for ((name, description) in screens) {
            if (description != null) {
                rule.onAllNodesWithContentDescription(description)[0].performClick()
                rule.waitForIdle()
            }
            val results = audit()
            report.add(name, results)
            dumpSemanticsOnError(name, results)
            if (description != null) {
                backToHome()
            }
        }
        val findings = report.publish(screens.size)
        assertTrue("${report.errors()} accessibility errors:\n$findings", report.errors() == 0)
    }

    /**
     * The 25 Settings screens. They hold most of the launcher's controls — switches, sliders,
     * pickers, capture consoles — and until now no audit had ever opened one.
     */
    @Test
    fun everySettingsScreenPassesTheAccessibilityPreset() {
        waitForHome()
        rule.onAllNodesWithContentDescription(SETTINGS_TILE)[0].performClick()
        rule.waitUntil(SCREEN_WAIT_MS) { onHub() }

        val report = AuditReport()
        for (row in settingsRows) {
            openRow(row)
            // A parked-only screen (the SysVar browser) shows its gate panel instead of the
            // content when the car is moving. The emulator reports 0 km/h so the content is
            // what appears; if it ever does not, the gate is a screen and gets audited too.
            val results = audit()
            report.add(row, results)
            dumpSemanticsOnError(row, results)
            backToHub(row)
        }
        val findings = report.publish(settingsRows.size)
        assertTrue("${report.errors()} accessibility errors:\n$findings", report.errors() == 0)
    }

    /**
     * A failing screen takes its semantics tree out with it. The check says what is wrong and
     * the bounds say where, but neither names the control; without the tree, reading "this item
     * may not have a label" against a screen of thirty rows costs another 15-minute CI round.
     */
    private fun dumpSemanticsOnError(screen: String, results: List<AccessibilityHierarchyCheckResult>) {
        if (results.none { it.type == AccessibilityCheckResultType.ERROR }) return
        for (line in rule.onRoot().printToString().lines()) {
            Log.i(TAG, "$screen | $line")
        }
    }

    /** Scroll the hub row into view, tap it, and wait for the hub to hand over. */
    private fun openRow(row: String) {
        rule.onAllNodesWithText(row)[0].performScrollTo().performClick()
        rule.waitUntil(SCREEN_WAIT_MS) { !onHub() }
        rule.waitForIdle()
    }

    /** Back until the hub is showing again; a screen that ate the press gets another. */
    private fun backToHub(row: String) {
        repeat(BACK_TRIES) {
            if (onHub()) return
            Espresso.pressBack()
            rule.waitForIdle()
        }
        if (!onHub()) throw AssertionError("Back from \"$row\" did not return to the settings hub")
    }

    /**
     * Two hub rows at once: a row title is also the title of the screen it opens, so a single
     * title cannot tell hub from screen, but no screen carries two of them.
     */
    private fun onHub(): Boolean = HUB_MARKERS.all { marker ->
        runCatching { rule.onAllNodesWithText(marker).fetchSemanticsNodes().isNotEmpty() }
            .getOrDefault(false)
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

    /**
     * The findings, collapsed on (screen, check, message). A list screen repeats one fault on
     * every row it draws, and 30 identical lines bury the other three findings; the count says
     * how many times each one occurred. One logcat line per distinct finding, then the tally.
     */
    private class AuditReport {

        private data class Finding(
            val screen: String,
            val type: AccessibilityCheckResultType,
            val check: String,
            val message: String,
        )

        private val counts = LinkedHashMap<Finding, Int>()
        // Class and bounds of the first element that raised each finding: the check's message
        // says what is wrong, never which control, and on a settings screen of thirty rows
        // that is the difference between a fix and a hunt.
        private val firstElement = HashMap<Finding, String>()
        private var total = 0

        fun add(screen: String, results: List<AccessibilityHierarchyCheckResult>) {
            for (result in results) {
                val finding = Finding(
                    screen = screen,
                    type = result.type,
                    check = result.sourceCheckClass.simpleName,
                    message = result.getMessage(java.util.Locale.ENGLISH).toString(),
                )
                counts[finding] = (counts[finding] ?: 0) + 1
                firstElement.getOrPut(finding) {
                    "${result.element?.className} @${result.element?.boundsInScreen}"
                }
                total++
            }
        }

        fun errors(): Int = counts.entries.filter { it.key.type == AccessibilityCheckResultType.ERROR }
            .sumOf { it.value }

        /** Logs the report and returns it for the failure message. */
        fun publish(screenCount: Int): String {
            val report = StringBuilder()
            for ((finding, count) in counts) {
                val repeats = if (count > 1) " x$count" else ""
                val line = "${finding.screen}: ${finding.type} ${finding.check} ${finding.message}" +
                    " ${firstElement[finding]}$repeats"
                Log.i(TAG, line)
                report.append(line).append('\n')
            }
            Log.i(
                TAG,
                "$screenCount screens, $total findings (${counts.size} distinct), ${errors()} errors",
            )
            return report.toString()
        }
    }

    private companion object {
        const val TAG = "A11yAudit"
        const val HOME_WAIT_MS = 60_000L
        const val SCREEN_WAIT_MS = 15_000L
        const val FLAG_SETTLE_MS = 1_000L
        const val ROOT_TRIES = 20
        const val ROOT_WAIT_MS = 250L
        const val BACK_TRIES = 3
        const val SETTINGS_TILE = "Settings"
        val HUB_MARKERS = listOf("App directory", "SysVar export")
    }
}
