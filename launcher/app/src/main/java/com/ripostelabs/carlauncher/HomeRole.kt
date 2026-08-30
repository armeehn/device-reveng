package com.ripostelabs.carlauncher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * v1.0 — full-replacement HOME helpers.
 *
 * This launcher is intentionally *companion-capable*: it registers MAIN + HOME + DEFAULT +
 * LAUNCHER (see AndroidManifest.xml) and works whether or not it is the current default HOME.
 * These helpers let the UI (Onboarding / Settings) both *detect* whether we are the default
 * and *ask* the user to make us the default, using the cleanest route each platform offers.
 *
 * TRUE, silent full replacement (no user picker, surviving factory-reset defaults) cannot be
 * done by a normal side-loaded app — Android reserves the persistent-preferred-home mapping to
 * the user's explicit choice or to a signature/privileged installer. To fully replace the stock
 * launcher (com.szchoiceway.customerui) without the chooser you must install as a system /
 * priv-app:
 *   - push the APK to /system/priv-app/CarLauncher/ (rooted unit) and reboot, and
 *   - optionally clear the vendor launcher's preferred-home association, e.g.
 *       pm set-home-activity com.ripostelabs.carlauncher/.MainActivity
 *     (or `cmd package set-home-activity …`), run as root.
 * Everything else here works from a plain user install.
 */
object HomeRole {

    private const val TAG = "HomeRole"

    /**
     * Are we the current default HOME? Resolves the HOME intent and compares the winning
     * package to ours. Returns false on any resolution failure (treated as "not default").
     */
    fun isDefaultHome(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        val pkg = resolved?.activityInfo?.packageName
        // When there is no persistent default the system may return the resolver
        // (android) — that also counts as "not us".
        return pkg == context.packageName
    }

    /**
     * Ask the platform to make us the default HOME, preferring the least-friction route:
     *   1. RoleManager ROLE_HOME request dialog (API 29+), when the role is both available and
     *      not already held — a single "Use Car Launcher as Home?" confirmation.
     *   2. The system Home-settings panel ([Settings.ACTION_HOME_SETTINGS]).
     *   3. General settings, as a last resort.
     *
     * Safe to call from any [Context]; adds NEW_TASK so it works from a non-Activity context.
     */
    fun requestSetDefaultHome(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null &&
                rm.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !rm.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                val ok = runCatching {
                    val intent = rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.onFailure { Log.w(TAG, "ROLE_HOME request failed, falling back", it) }.isSuccess
                if (ok) return
            }
        }
        openHomeSettings(context)
    }

    /** Open the system Home-settings panel (where the default HOME is chosen), with fallback. */
    fun openHomeSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { e -> Log.w(TAG, "no settings activity resolvable", e) }
        }
    }
}
