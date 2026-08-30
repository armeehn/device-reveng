package com.reveng.carlauncher.data

/**
 * v0.5 — the standalone `com.reveng.*` app suite (repo `armeehn/rav4-apps`).
 *
 * Those apps are clean-room rewrites of the OEM ones, but they are **not** overlays: each is an
 * ordinary package with its own name, no `sharedUserId` and no platform signature. That pivot is
 * what makes them installable at all — a same-package overlay is refused for every OEM app that
 * shares `android.uid.system`, and stubbing a gateway-reflected package takes out the reverse
 * camera and the steering-wheel controls.
 *
 * The consequence for the launcher is that a suite app looks like any other third-party app to
 * PackageManager. Nothing in the system marks the twenty-six of them as one family, so the
 * launcher carries the registry: membership is this explicit list, never a prefix match.
 * [PACKAGE_PREFIX] alone would swallow the launcher itself (`com.reveng.carlauncher`) and its
 * debug sibling.
 *
 * The list is deliberately static. Reading it off the device at runtime would make the drawer's
 * grouping depend on install order, and an app that fails to install would silently vanish from
 * the suite instead of being reported missing by [SetupDoctor].
 */
object RevengSuite {

    /** Shared by every suite package — and by the launcher, which is why it never classifies. */
    const val PACKAGE_PREFIX = "com.reveng."

    /** One member of the suite. [label] is the app's own `app_name`, used when it is absent. */
    data class SuiteApp(val packageName: String, val label: String)

    /**
     * The suite as built. Order is alphabetical by label so the "missing" report and the drawer
     * folder read the same way every time.
     */
    val APPS: List<SuiteApp> = listOf(
        SuiteApp("com.reveng.bluetooth", "Bluetooth"),
        SuiteApp("com.reveng.browser", "Browser"),
        SuiteApp("com.reveng.calculator", "Calculator"),
        SuiteApp("com.reveng.calendar", "Calendar"),
        SuiteApp("com.reveng.clock", "Clock"),
        SuiteApp("com.reveng.compass", "Compass"),
        SuiteApp("com.reveng.contacts", "Contacts"),
        SuiteApp("com.reveng.converter", "Converter"),
        SuiteApp("com.reveng.currency", "Currency"),
        SuiteApp("com.reveng.deviceinfo", "Device Info"),
        SuiteApp("com.reveng.files", "Files"),
        SuiteApp("com.reveng.gps", "GPS"),
        SuiteApp("com.reveng.installer", "Installer"),
        SuiteApp("com.reveng.level", "Level"),
        SuiteApp("com.reveng.music", "Music"),
        SuiteApp("com.reveng.news", "News"),
        SuiteApp("com.reveng.notes", "Notes"),
        SuiteApp("com.reveng.photos", "Photos"),
        SuiteApp("com.reveng.radio", "Radio"),
        SuiteApp("com.reveng.recorder", "Recorder"),
        SuiteApp("com.reveng.sketch", "Sketch"),
        SuiteApp("com.reveng.soundmeter", "Sound Meter"),
        SuiteApp("com.reveng.speedometer", "Speedometer"),
        SuiteApp("com.reveng.tasks", "Tasks"),
        SuiteApp("com.reveng.video", "Video"),
        SuiteApp("com.reveng.weather", "Weather"),
    )

    private val byPackage: Map<String, SuiteApp> = APPS.associateBy { it.packageName }

    /** True only for a registered member — the launcher's own package is not one. */
    fun isSuiteApp(packageName: String): Boolean = packageName in byPackage

    /** The suite members present in [installedPackages], in registry order. */
    fun installed(installedPackages: Set<String>): List<SuiteApp> =
        APPS.filter { it.packageName in installedPackages }

    /** The suite members absent from [installedPackages], in registry order. */
    fun missing(installedPackages: Set<String>): List<SuiteApp> =
        APPS.filterNot { it.packageName in installedPackages }
}
