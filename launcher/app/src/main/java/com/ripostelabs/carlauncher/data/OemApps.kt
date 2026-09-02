package com.ripostelabs.carlauncher.data

/**
 * The Choiceway OEM apps and what this launcher does about each one — the replacement matrix
 * of `OEM_SYSTEM.md` §1, as code.
 *
 * Every vendor app runs as `android.uid.system` under a platform key we do not hold, so
 * "replace" can never mean overwrite. It means: ship our own package, hide the original from
 * the drawer once ours is there, and name the leftovers for `pm uninstall -k --user 0`. This
 * object decides the hiding; nothing here executes an uninstall.
 *
 * ```
 *   OEM package         class      drawer
 *   ───────────         ─────      ──────
 *   photoreader         REMOVE     hidden always; Setup Doctor prints the uninstall line
 *   musicplayer         REPLACED   hidden only while com.ripostelabs.music is installed
 *   settings            REPLACED   hidden only when the driver opts in (factory menu lives there)
 *   navigation          REPLACED   never hidden: no camera viewer exists yet
 *   canbus2             KEEP       never hidden; Setup Doctor warns when it is missing
 * ```
 *
 * The rule that matters most: a REPLACED app whose replacement is missing stays visible. A unit
 * with none of the suite installed keeps every OEM app and stays usable.
 */
object OemApps {

    enum class OemClass { REMOVE, REPLACED, KEEP }

    /** Something of ours that stands in for an OEM app. */
    sealed interface Replacement {
        /** Present only when the package is installed. */
        data class Package(val packageName: String) : Replacement

        /** A screen inside this launcher: present by construction. */
        data class LauncherScreen(val name: String) : Replacement
    }

    /** Which opt-in, if any, a REPLACED app needs before the drawer hides it. */
    enum class OptIn { NONE, OEM_SETTINGS }

    data class OemApp(
        val packageName: String,
        /** The app's own launcher label, as the drawer shows it. */
        val label: String,
        val oemClass: OemClass,
        /** One line for the Setup Doctor: what it is, why it is classed this way. */
        val why: String,
        /** All of these must be present for a REPLACED app to be hidden. Empty = nothing yet. */
        val replacedBy: List<Replacement> = emptyList(),
        val optIn: OptIn = OptIn.NONE,
    )

    /** What the driver has allowed the shadow to do (SettingsStore, Launcher ▸ Vendor apps). */
    data class ShadowPolicy(val hideReplaced: Boolean, val hideOemSettings: Boolean) {
        companion object {
            val DEFAULT = ShadowPolicy(hideReplaced = true, hideOemSettings = false)

            fun from(settings: LauncherSettings) = ShadowPolicy(
                hideReplaced = settings.hideReplacedOemApps,
                hideOemSettings = settings.hideOemSettings,
            )
        }
    }

    /** What the Setup Doctor's "Vendor apps" section shows. Pure data; the screen renders it. */
    data class Report(
        /** What the probe saw installed; [pendingReason] reads it. */
        val installed: Set<String>,
        /** Hidden from the drawer right now. */
        val shadowed: List<OemApp>,
        /** REPLACED apps still visible because a replacement (or the opt-in) is missing. */
        val pending: List<OemApp>,
        /** REMOVE apps still on the unit. */
        val removable: List<OemApp>,
        /** KEEP apps absent or disabled: the unit is missing part of its own plumbing. */
        val missingKeep: List<OemApp>,
    ) {
        val uninstallLines: List<String> = removable.map { uninstallLine(it.packageName) }
    }

    const val UNINSTALL_PREFIX = "pm uninstall -k --user 0 "

    const val RADIO_SCREEN = "Radio screen"
    const val SETTINGS_SCREEN = "Settings"

    private const val RADIO_PKG = "com.ripostelabs.radio"
    private const val MUSIC_PKG = "com.ripostelabs.music"
    private const val VIDEO_PKG = "com.ripostelabs.video"
    private const val GPS_PKG = "com.ripostelabs.gps"

    val APPS: List<OemApp> = listOf(
        // ---- REMOVE: nothing of ours depends on them, and two are liabilities --------------
        OemApp(
            "com.szchoiceway.photoreader", "XDemonstrate", OemClass.REMOVE,
            "A 16-image demo slideshow. Photos is a real viewer.",
        ),
        OemApp(
            "com.szchoiceway.apkinstall", "Apk Installer", OemClass.REMOVE,
            "Silent pm install -r from any USB stick. Installer replaces it.",
        ),
        OemApp(
            "com.choiceway.weather", "Weather", OemClass.REMOVE,
            "Seniverse (China) API client. Weather has its own source.",
        ),
        OemApp(
            "com.mmbox.xbrowser", "XBrowser", OemClass.REMOVE,
            "Chinese browser. Browser replaces it.",
        ),
        OemApp(
            "com.android.atslcarconsole", "Console", OemClass.REMOVE,
            "Cadillac ATS-L console: writes raw CAN frames for the wrong car.",
        ),

        // ---- REPLACED: hidden only while our replacement is present -------------------------
        OemApp(
            "com.szchoiceway.radio", "Radio", OemClass.REPLACED,
            "Tuner UI. Hidden once the suite radio is installed beside the launcher's own screen.",
            replacedBy = listOf(Replacement.LauncherScreen(RADIO_SCREEN), Replacement.Package(RADIO_PKG)),
        ),
        OemApp(
            "com.szchoiceway.musicplayer", "Music", OemClass.REPLACED,
            "USB/local player.",
            replacedBy = listOf(Replacement.Package(MUSIC_PKG)),
        ),
        OemApp(
            "com.szchoiceway.videoplayer", "HD movies", OemClass.REPLACED,
            "Video player with the handbrake gate.",
            replacedBy = listOf(Replacement.Package(VIDEO_PKG)),
        ),
        OemApp(
            "com.szchoiceway.gps", "GPS", OemClass.REPLACED,
            "GNSS status page.",
            replacedBy = listOf(Replacement.Package(GPS_PKG)),
        ),
        OemApp(
            "com.szchoiceway.settings", "System", OemClass.REPLACED,
            "SysVar editor. Still hosts the factory menu, so hiding it is opt-in.",
            replacedBy = listOf(Replacement.LauncherScreen(SETTINGS_SCREEN)),
            optIn = OptIn.OEM_SETTINGS,
        ),
        OemApp(
            "com.szchoiceway.navigation", "Navigation", OemClass.REPLACED,
            "Misnamed: the front/rear/blind camera and HDMI viewer. Visible until we have one.",
        ),

        // ---- KEEP: the hardware path, the factory tools, the proprietary receiver -----------
        OemApp(
            "com.szchoiceway.eventcenter", "EventCenter", OemClass.KEEP,
            "The MCU gateway. Everything talks to the car through it.",
        ),
        OemApp(
            "com.szchoiceway.canbus2", "Canbus", OemClass.KEEP,
            "CAN decode, HVAC, doors, radar. Safety.",
        ),
        OemApp(
            "com.szchoiceway.customerui", "Home", OemClass.KEEP,
            "Stock home; the gateway inflates it by name.",
        ),
        OemApp(
            "com.szchoiceway.btsuite", "Bluetooth", OemClass.KEEP,
            "Owns the BT module protocol (phone, contacts, A2DP).",
        ),
        OemApp(
            "com.zjinnova.zlink", "ZLINK5", OemClass.KEEP,
            "CarPlay / Android Auto receiver. Proprietary.",
        ),
        OemApp(
            "com.szchoiceway.learn.key", "ZXWLib", OemClass.KEEP,
            "Steering-wheel and panel key learning. Factory tool.",
        ),
        OemApp(
            "com.szchoiceway.canbusdebug", "CanbusDebug", OemClass.KEEP,
            "MCU/CAN frame injector. Diagnostics.",
        ),
        OemApp(
            "com.lfg.szchoiceway.canupgrade", "CanUpgrade", OemClass.KEEP,
            "MCU/CAN/BT firmware flasher. Bricks the MCU if misused.",
        ),
        OemApp(
            "com.szchoiceway.zxwmedia", "zxwmedia", OemClass.KEEP,
            "Background media scanner. Harmless; replaced by omission.",
        ),
    )

    private val byPackage: Map<String, OemApp> = APPS.associateBy { it.packageName }

    fun byPackage(packageName: String): OemApp? = byPackage[packageName]

    /** The exact line a human runs over `adb shell` (or a root shell). Never run from here. */
    fun uninstallLine(packageName: String): String = UNINSTALL_PREFIX + packageName

    /**
     * OEM packages the drawer hides, given what is [installed] and what the driver allowed.
     * REMOVE hides unconditionally; REPLACED hides only with every replacement present and the
     * policy in favour; KEEP never hides.
     */
    fun shadowed(installed: Set<String>, policy: ShadowPolicy): Set<String> =
        APPS.filter { it.packageName in installed && hides(it, installed, policy) }
            .mapTo(mutableSetOf()) { it.packageName }

    private fun hides(app: OemApp, installed: Set<String>, policy: ShadowPolicy): Boolean =
        when (app.oemClass) {
            OemClass.REMOVE -> true
            OemClass.KEEP -> false
            OemClass.REPLACED -> allowed(app, policy) && replaced(app, installed)
        }

    /** The opt-in gate. Plain REPLACED apps follow the main toggle; OEM settings has its own. */
    private fun allowed(app: OemApp, policy: ShadowPolicy): Boolean =
        when (app.optIn) {
            OptIn.NONE -> policy.hideReplaced
            OptIn.OEM_SETTINGS -> policy.hideOemSettings
        }

    /** True only when there is at least one replacement and every one of them is present. */
    private fun replaced(app: OemApp, installed: Set<String>): Boolean =
        app.replacedBy.isNotEmpty() && app.replacedBy.all { present(it, installed) }

    private fun present(replacement: Replacement, installed: Set<String>): Boolean =
        when (replacement) {
            is Replacement.LauncherScreen -> true
            is Replacement.Package -> replacement.packageName in installed
        }

    /**
     * The Setup Doctor's view. [installed] is what PackageManager lists; [enabled] the subset
     * that is not disabled, which is what "the unit still has its gateway" actually means.
     */
    fun report(installed: Set<String>, enabled: Set<String>, policy: ShadowPolicy): Report {
        val hidden = shadowed(installed, policy)
        val onUnit = APPS.filter { it.packageName in installed }

        return Report(
            installed = installed,
            shadowed = onUnit.filter { it.packageName in hidden },
            pending = onUnit.filter { it.oemClass == OemClass.REPLACED && it.packageName !in hidden },
            removable = onUnit.filter { it.oemClass == OemClass.REMOVE },
            missingKeep = APPS.filter { it.oemClass == OemClass.KEEP && it.packageName !in enabled },
        )
    }

    /** Why a REPLACED app is still visible: the missing replacement, or the opt-in it waits on. */
    fun pendingReason(app: OemApp, installed: Set<String>): String {
        if (app.replacedBy.isEmpty()) {
            return "no replacement yet"
        }
        val missing = app.replacedBy.filterNot { present(it, installed) }
        if (missing.isNotEmpty()) {
            return "needs " + missing.joinToString { describe(it) }
        }
        return when (app.optIn) {
            OptIn.OEM_SETTINGS -> "hidden only if you turn on \"Hide OEM System settings\""
            OptIn.NONE -> "hidden only if you turn on \"Hide replaced OEM apps\""
        }
    }

    private fun describe(replacement: Replacement): String = when (replacement) {
        is Replacement.LauncherScreen -> "the launcher's ${replacement.name}"
        is Replacement.Package -> replacement.packageName
    }
}
