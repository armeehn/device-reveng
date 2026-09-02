package com.ripostelabs.carlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.Log
import com.ripostelabs.carlauncher.data.RiposteSuite

/** A launchable app resolved from the system (PackageManager). */
data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    /** True = vendor/engineering/system app hidden behind the "System" folder. */
    val isSystem: Boolean,
)

/**
 * Enumerates and launches installed apps for the drawer (CAR_API §6.3, "App list").
 *
 * Apps are classified into "user" (main grid) vs "system" (tucked into a System folder) so
 * the home screen isn't cluttered with vendor/engineering tools (TestTools, CanbusDebug,
 * ApkInstall, the atslcarconsole shell, AOSP samples, …). Classification is data-driven via
 * [alwaysShow] / [alwaysHidePrefixes]; the raw FLAG_SYSTEM bit is the fallback.
 *
 * On API 30+ enumeration depends on package visibility — see QUERY_ALL_PACKAGES / <queries>
 * in AndroidManifest.xml.
 */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /** Curated launchers that ARE system apps but should always stay on the home grid. */
    private val alwaysShow = setOf(
        "com.android.vending",              // Play Store
        "com.google.android.apps.maps",     // Maps
        "com.android.settings",             // Settings
        "com.topjohnwu.magisk",             // Magisk
        "com.android.chrome",
        "com.google.android.projection.gearhead", // Android Auto
        "com.google.android.googlequicksearchbox",
        "org.codeaurora.snapcam",           // camera
        // Zlink phone-projection receiver. It keeps exactly one launcher alias enabled for
        // whichever protocol is configured (features.launcher.CarPlayActivity today; the
        // AutoActivity/HiCarActivity/... aliases when the unit is switched), so this surfaces
        // a single "CarPlay" tile on the main grid rather than the whole vendor suite.
        "com.zjinnova.zlink",
    )

    /**
     * Never shown in the drawer, search, or app directory: the launcher itself (both build
     * variants — the release/debug sibling otherwise shows up as a second "Car Launcher")
     * and the Claude apps, which live on the quick-launch grid instead (see HomeScreen's
     * pinned slots, resolved through [resolveApp]).
     */
    private val hiddenFromUiPrefixes = listOf(
        "com.ripostelabs.carlauncher",
        "com.ripostelabs.claudecar",
    )

    /** Package prefixes to always push into the System folder regardless of flags. */
    private val alwaysHidePrefixes = listOf(
        "com.szchoiceway.",
        "com.choiceway.",
        "com.lfg.szchoiceway.",
        "com.zjinnova.",                    // zlink internals (com.zjinnova.zlink itself is alwaysShow)
        "com.ivicar.",
        "com.syu.",
        "com.android.atslcarconsole",       // vendor console shell
        "com.example.android.",             // AOSP sample leftovers
        "com.mmbox.",
    )

    private fun classifySystem(ai: ApplicationInfo): Boolean {
        val pkg = ai.packageName
        if (pkg in alwaysShow) return false
        if (alwaysHidePrefixes.any { pkg.startsWith(it) }) return true
        val sys = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        return sys != 0
    }

    /** All MAIN/LAUNCHER activities, sorted by label, minus ourselves, tagged user/system. */
    fun loadApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = PackageManager.ResolveInfoFlags.of(0L)
        val resolved: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, flags)
        } catch (t: Throwable) {
            Log.e("AppRepository", "queryIntentActivities failed", t)
            emptyList()
        }

        val self = context.packageName

        // A retired com.reveng.* suite package beside its rewrite is a second "Clock" with the
        // same icon that never follows the theme. Shadow it; SetupDoctor names it for removal.
        val present = resolved.mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
        val shadowed = RiposteSuite.retiredTwins(present)

        return resolved.asSequence()
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                if (ai.packageName == self) return@mapNotNull null
                if (hiddenFromUiPrefixes.any { ai.packageName.startsWith(it) }) return@mapNotNull null
                if (ai.packageName in shadowed) return@mapNotNull null
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ai.packageName,
                    activityName = ai.name,
                    icon = ri.loadIcon(pm),
                    isSystem = classifySystem(ai.applicationInfo),
                )
            }
            .distinctBy { it.packageName + "/" + it.activityName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Resolve a single package into an [AppInfo], including ones [loadApps] filters out of
     * the UI lists (the quick-launch grid pins Claude, which is hidden from the drawer).
     * Null when the package isn't installed or has no launchable activity.
     */
    fun resolveApp(packageName: String): AppInfo? {
        val launch = pm.getLaunchIntentForPackage(packageName) ?: return null
        val component = launch.component ?: return null
        val ri = pm.resolveActivity(launch, PackageManager.ResolveInfoFlags.of(0L)) ?: return null
        return AppInfo(
            label = ri.loadLabel(pm).toString(),
            packageName = packageName,
            activityName = component.className,
            icon = ri.loadIcon(pm),
            isSystem = false,
        )
    }

    /** Launch an app. Falls back to the package's default launch intent. */
    fun launch(app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(app.packageName, app.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w("AppRepository", "explicit launch failed, trying default intent", t)
            pm.getLaunchIntentForPackage(app.packageName)?.let {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                runCatching { context.startActivity(it) }
            }
        }
    }
}
