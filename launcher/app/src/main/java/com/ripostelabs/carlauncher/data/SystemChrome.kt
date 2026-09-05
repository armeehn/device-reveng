package com.ripostelabs.carlauncher.data

import android.util.Log
import com.ripostelabs.carlauncher.carlib.RootShell

/**
 * v2.5 — suppression of the vendor/system top chrome, so the launcher's own shade
 * ([com.ripostelabs.carlauncher.ui.ShadeOverlay]) replaces it instead of stacking under it.
 *
 * Two independent mechanisms, both confirmed on-device (GT6-EAU T16.00.050, 2026-08-28). They are
 * run as separate shell commands, because they really are independent: a non-zero exit from the
 * `cmd statusbar` half must not drop the `setprop` half.
 *
 *  * `cmd statusbar disable-for-setup true` — StatusBarManager's setup-mode disable flags.
 *    Verified to fully block the SystemUI notification shade (`expand-settings` becomes a
 *    no-op). Runtime state only: it resets whenever SystemUI restarts (every boot), so it is
 *    re-asserted on every launcher start while the setting is on.
 *  * `persist.sys.show_statusbar 0` — the vendor framework prop behind the transient
 *    top status bar (the 60 px `STATUS_BAR` window that slides in on a top-edge swipe;
 *    height from eventcenter `utils/SystemUtils.java:121-123`). SystemUI reads it at
 *    startup only.
 *
 * v3.0 — the prop is NOT reboot-persistent in practice. The gateway rewrites it to `1` on
 * every branch of `SystemUtils.initNaviAndStatusBarHeight` (eventcenter
 * `utils/SystemUtils.java:106,115,136,149,156,160`), which runs from its boot runnable
 * (`EventService.java:1447`) and again on config restore (`:11254`, `:14182`). So by the time
 * SystemUI comes up the prop says `1` again and the pull-down is back (car test, build 202).
 * No SysVar or vendor-settings toggle gates that write: `Sys_customer_statusbar` is an OUTPUT
 * the same method forces to `1` (`:161`), and the gateway's own `CustomStatusbar` window
 * (`floatingwindow/CustomStatusbar.java:32`, type 2017) only exists for `Sys_CustomerType`
 * 53 (`EventService.java:14247`) and opens on the `StatusBar` launcher keyword, not a swipe.
 * The fix is therefore to write the prop AFTER the gateway did and make SystemUI re-read it:
 * restart SystemUI (system_server respawns it) when the prop was found in the wrong state.
 * One restart per boot, ~1-2 s of blank chrome. UNVERIFIED on the car: that the vendor
 * SystemUI honours the prop on a mid-boot restart the same way it does on a cold start.
 *
 * Both writes go through [RootShell] and degrade to a no-op result off-device / without
 * root. Everything is reversible via [apply] (false) — no vendor component is disabled
 * or replaced.
 */
object SystemChrome {

    private const val TAG = "SystemChrome"

    /** Vendor framework prop SystemUI reads at startup to decide whether to add a status bar. */
    internal const val SHOW_STATUSBAR_PROP = "persist.sys.show_statusbar"
    internal const val PROP_SHOWN = "1"
    internal const val PROP_HIDDEN = "0"

    /** SystemUI is a persistent app: killing it makes system_server respawn it at once. */
    internal const val RESTART_SYSTEMUI_CMD = "killall com.android.systemui"

    /** Which chrome the driver asked for. */
    enum class Bars { VENDOR, LAUNCHER }

    /**
     * Suppress (true) or restore (false) the system top bars. BLOCKING — call from
     * Dispatchers.IO. Safe to call repeatedly; used both from the settings toggle and to
     * re-assert the choice on every launcher start (the `cmd statusbar` half is lost on
     * reboot, the prop half is reverted by the gateway on every boot).
     *
     * @return true when the shell commands ran ok (root available).
     */
    fun apply(replaceBars: Boolean): Boolean {
        val bars = if (replaceBars) Bars.LAUNCHER else Bars.VENDOR

        // Read what the gateway left behind; null when root/getprop is unavailable.
        val current = RootShell.exec("getprop $SHOW_STATUSBAR_PROP")
            .takeIf { it.ok }
            ?.out
            ?.firstOrNull()
            ?.trim()

        val res = RootShell.exec(*plan(bars, current).toTypedArray())
        if (!res.ok) Log.w(TAG, "apply(replaceBars=$replaceBars) failed: ${res.failures}")
        return res.ok
    }

    /**
     * The shell commands for [bars], given the prop value currently in effect
     * ([currentProp], null = unknown). Pure, so the restart decision is unit-tested:
     * SystemUI is restarted only when the prop was not already what we want — i.e. once per
     * boot after the gateway reverted it — never on the routine re-assert.
     */
    internal fun plan(bars: Bars, currentProp: String?): List<String> {
        val wanted = if (bars == Bars.LAUNCHER) PROP_HIDDEN else PROP_SHOWN

        val commands = mutableListOf<String>()
        if (bars == Bars.LAUNCHER) {
            commands += "cmd statusbar disable-for-setup true"
            commands += "cmd statusbar collapse"
        } else {
            commands += "cmd statusbar disable-for-setup false"
        }
        commands += "setprop $SHOW_STATUSBAR_PROP $wanted"

        // Already in effect: SystemUI read this value at its start, nothing to bounce.
        if (currentProp == wanted) {
            return commands
        }

        commands += RESTART_SYSTEMUI_CMD
        return commands
    }
}
