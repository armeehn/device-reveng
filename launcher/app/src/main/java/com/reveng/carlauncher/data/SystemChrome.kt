package com.reveng.carlauncher.data

import android.util.Log
import com.reveng.carlauncher.carlib.RootShell

/**
 * v2.5 — suppression of the vendor/system top chrome, so the launcher's own shade
 * ([com.reveng.carlauncher.ui.ShadeOverlay]) replaces it instead of stacking under it.
 *
 * Two independent mechanisms, both confirmed on-device (GT6-EAU T16.00.050, 2026-08-28). They are
 * run as separate shell commands, because they really are independent: a non-zero exit from the
 * `cmd statusbar` half must not drop the `setprop` half, which is the only reboot-persistent one.
 *
 *  * `cmd statusbar disable-for-setup true` — StatusBarManager's setup-mode disable flags.
 *    Verified to fully block the SystemUI pull-down (`expand-settings` becomes a no-op).
 *    Runtime state only: it resets whenever SystemUI restarts (every boot), so it is
 *    re-asserted on every launcher start while the setting is on.
 *  * `persist.sys.show_statusbar 0` — the vendor framework prop behind the transient
 *    top status bar (the 60 px `STATUS_BAR` window that slides in on a top-edge swipe).
 *    Read at SystemUI startup, so it takes effect from the next boot.
 *
 * Both writes go through [RootShell] and degrade to a no-op result off-device / without
 * root. Everything is reversible via [apply] (false) — no vendor component is disabled
 * or replaced.
 */
object SystemChrome {

    private const val TAG = "SystemChrome"

    /**
     * Suppress (true) or restore (false) the system top bars. BLOCKING — call from
     * Dispatchers.IO. Safe to call repeatedly; used both from the settings toggle and to
     * re-assert the choice on every launcher start (the `cmd statusbar` half is lost on
     * reboot).
     *
     * @return true when the shell commands ran ok (root available).
     */
    fun apply(replaceBars: Boolean): Boolean {
        val res = if (replaceBars) {
            RootShell.exec(
                "cmd statusbar disable-for-setup true",
                "cmd statusbar collapse",
                "setprop persist.sys.show_statusbar 0",
            )
        } else {
            RootShell.exec(
                "cmd statusbar disable-for-setup false",
                "setprop persist.sys.show_statusbar 1",
            )
        }
        if (!res.ok) Log.w(TAG, "apply(replaceBars=$replaceBars) failed: ${res.failures}")
        return res.ok
    }
}
