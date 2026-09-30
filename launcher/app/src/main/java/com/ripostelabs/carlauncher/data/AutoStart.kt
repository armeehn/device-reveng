package com.ripostelabs.carlauncher.data

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay

/**
 * RAV4-176: start a chosen app when the car comes on, as stock's
 * `sys_custom_auto_start_app_pkg_cls_tab` does (ItemTextRightCheckBoxView.java:150-173).
 *
 *     launcher start ──▶ LAUNCH ──▶ once per BOOT_COUNT ──┐
 *     ACC off ─▶ on  ──▶ WAKE   ──▶ every time ───────────┴──▶ home settles ──▶ start the app
 *
 * A launcher restart in the same boot (update, crash) is not the car coming on, so LAUNCH is
 * gated on the boot count. Blank, the default, starts nothing.
 */
object AutoStart {

    enum class Trigger { LAUNCH, WAKE }

    private const val TAG = "AutoStart"
    private const val PREFS = "auto_start"
    private const val KEY_BOOT = "started_boot"
    private const val NO_BOOT = -1

    /** Home draws first, so Back from the app lands on a live launcher. */
    private const val HOME_SETTLE_MS = 2_000L

    fun due(trigger: Trigger, pkg: String, bootCount: Int?, lastBoot: Int): Boolean {
        if (pkg.isBlank()) {
            return false
        }

        return when (trigger) {
            Trigger.WAKE -> true
            Trigger.LAUNCH -> bootCount != null && bootCount != lastBoot
        }
    }

    suspend fun run(context: Context, trigger: Trigger, pkg: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, NO_BOOT)
            .takeIf { it != NO_BOOT }
        if (!due(trigger, pkg, boot, prefs.getInt(KEY_BOOT, NO_BOOT))) {
            return
        }

        prefs.edit().putInt(KEY_BOOT, boot ?: NO_BOOT).apply()
        delay(HOME_SETTLE_MS)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        Log.i(TAG, "$trigger: starting $pkg")
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "cannot start $pkg", it) }
    }

    /** Every app with a launcher icon but this one, by label: the picker's list. */
    fun apps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
}
