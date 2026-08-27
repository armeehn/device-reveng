package com.reveng.carlauncher

import android.content.Context
import android.content.Intent
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
)

/**
 * Enumerates and launches installed apps for the drawer (CAR_API §6.3, "App list").
 *
 * On API 30+ this depends on package visibility — see the QUERY_ALL_PACKAGES permission /
 * <queries> element in AndroidManifest.xml.
 */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /** All MAIN/LAUNCHER activities, sorted by label, minus ourselves. */
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
                    icon = ri.loadIcon(pm),
                )
            }
            .distinctBy { it.packageName + "/" + it.activityName }
            .sortedBy { it.label.lowercase() }
            .toList()
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
