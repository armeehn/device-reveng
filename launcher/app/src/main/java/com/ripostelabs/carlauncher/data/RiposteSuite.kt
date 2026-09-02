package com.ripostelabs.carlauncher.data

/**
 * v0.5 — the standalone `com.ripostelabs.*` app suite (repo `armeehn/rav4-apps`).
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
 * [PACKAGE_PREFIX] alone would swallow the launcher itself (`com.ripostelabs.carlauncher`) and its
 * debug sibling.
 *
 * The list is deliberately static. Reading it off the device at runtime would make the drawer's
 * grouping depend on install order, and an app that fails to install would silently vanish from
 * the suite instead of being reported missing by [SetupDoctor].
 */
object RiposteSuite {

    /** Shared by every suite package — and by the launcher, which is why it never classifies. */
    const val PACKAGE_PREFIX = "com.ripostelabs."

    /**
     * The prefix the suite shipped under before the rename (2026-08-30). A package left over
     * from that era queries a theme authority this launcher no longer publishes, so it keeps
     * its built-in look under every theme — while carrying the same label and icon as the
     * rewrite installed beside it. Nothing on the device tells the two apart.
     */
    const val RETIRED_PREFIX = "com.reveng."

    /** One member of the suite. [label] is the app's own `app_name`, used when it is absent. */
    data class SuiteApp(val packageName: String, val label: String)

    /**
     * The suite as built. Order is alphabetical by label so the "missing" report and the drawer
     * folder read the same way every time.
     */
    val APPS: List<SuiteApp> = listOf(
        SuiteApp("com.ripostelabs.bluetooth", "Bluetooth"),
        SuiteApp("com.ripostelabs.browser", "Browser"),
        SuiteApp("com.ripostelabs.calculator", "Calculator"),
        SuiteApp("com.ripostelabs.calendar", "Calendar"),
        SuiteApp("com.ripostelabs.clock", "Clock"),
        SuiteApp("com.ripostelabs.compass", "Compass"),
        SuiteApp("com.ripostelabs.contacts", "Contacts"),
        SuiteApp("com.ripostelabs.converter", "Converter"),
        SuiteApp("com.ripostelabs.currency", "Currency"),
        SuiteApp("com.ripostelabs.deviceinfo", "Device Info"),
        SuiteApp("com.ripostelabs.files", "Files"),
        SuiteApp("com.ripostelabs.gps", "GPS"),
        SuiteApp("com.ripostelabs.installer", "Installer"),
        SuiteApp("com.ripostelabs.level", "Level"),
        SuiteApp("com.ripostelabs.music", "Music"),
        SuiteApp("com.ripostelabs.news", "News"),
        SuiteApp("com.ripostelabs.notes", "Notes"),
        SuiteApp("com.ripostelabs.photos", "Photos"),
        SuiteApp("com.ripostelabs.radio", "Radio"),
        SuiteApp("com.ripostelabs.recorder", "Recorder"),
        SuiteApp("com.ripostelabs.sketch", "Sketch"),
        SuiteApp("com.ripostelabs.soundmeter", "Sound Meter"),
        SuiteApp("com.ripostelabs.speedometer", "Speedometer"),
        SuiteApp("com.ripostelabs.tasks", "Tasks"),
        SuiteApp("com.ripostelabs.video", "Video"),
        SuiteApp("com.ripostelabs.weather", "Weather"),
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

    /**
     * Retired `com.reveng.*` packages in [installedPackages] whose rewrite is installed too.
     * Only those: with no rewrite present the retired package is still the owner's only copy.
     */
    fun retiredTwins(installedPackages: Set<String>): List<String> =
        APPS.map { RETIRED_PREFIX + it.packageName.removePrefix(PACKAGE_PREFIX) }
            .filter { it in installedPackages && liveTwin(it) in installedPackages }

    /** The rewrite a retired package was renamed to; any other package unchanged. */
    fun liveTwin(packageName: String): String {
        if (!packageName.startsWith(RETIRED_PREFIX)) {
            return packageName
        }
        val live = PACKAGE_PREFIX + packageName.removePrefix(RETIRED_PREFIX)
        return if (live in byPackage) live else packageName
    }
}
