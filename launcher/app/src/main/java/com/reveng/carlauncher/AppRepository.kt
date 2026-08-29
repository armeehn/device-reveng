package com.reveng.carlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.Log

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
        return resolved.asSequence()
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                if (ai.packageName == self) return@mapNotNull null
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ai.packageName,
                    activityName = ai.name,
                    icon = rewrittenIcon(ai.packageName) ?: ri.loadIcon(pm),
                    isSystem = classifySystem(ai.applicationInfo),
                )
            }
            .distinctBy { it.packageName + "/" + it.activityName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * v0.4.6 — the clean-room OEM-app rewrites (rav4-apps, all under `com.reveng.`) share one
     * bugdroid drawer icon so they read as a family. The launcher itself and Claude Car keep
     * their own branding. Fresh drawable per app: toBitmap mutates drawable bounds at render.
     */
    private fun rewrittenIcon(pkg: String): Drawable? {
        if (!pkg.startsWith("com.reveng.")) return null
        if (pkg.startsWith("com.reveng.carlauncher") || pkg.startsWith("com.reveng.claudecar")) {
            return null
        }
        return context.getDrawable(R.drawable.ic_rewritten_app)
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
