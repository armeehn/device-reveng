package com.reveng.carlauncher.carlib

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * v2.9 — sole-HOME mode: disabling the vendor launcher package, reversibly.
 *
 * This is the most destructive thing the launcher can do, so the mechanics are here in the driver
 * layer with the recovery attached to them, not spread across a settings screen.
 *
 * ### What is actually at risk
 *
 * `com.szchoiceway.customerui` is **not** an Android HOME app — no activity of it declares
 * `category.HOME` (CUSTOMERUI_NOTES §0). Disabling it therefore cannot orphan the HOME role, and
 * our own launcher keeps resolving HOME either way. The real risk is elsewhere: the gateway
 * inflates its shared status bar and side-window layouts *out of* customerui via
 * `createPackageContext` (CUSTOMERUI_NOTES §3g). With the package disabled those inflations fail,
 * and how gracefully the gateway handles that is unknown. A gateway that crash-loops on a car's
 * screen is not something a user can debug from the driver's seat.
 *
 * ### The recovery has to survive us
 *
 * An in-app "undo" is worthless here: if disabling the vendor launcher destabilises the gateway
 * badly enough to matter, our process is not necessarily the one still running. So [disable] arms
 * the rollback **before** it does the damage, in a detached root shell that init reparents:
 *
 * ```
 * sleep <window>; [ -f <keep-file> ] || pm enable com.szchoiceway.customerui; rm -f <keep-file>
 * ```
 *
 * The launcher can then be killed, ANR'd, or uninstalled and the vendor launcher still comes back
 * on its own. [keepDisabled] writes the keep-file, which is how the user says "it worked, stop
 * watching".
 *
 * **The one gap, stated plainly:** a reboot or an ACC power-off inside the window kills the
 * detached shell, and the rollback never fires. That case needs the manual recovery documented in
 * `launcher/README.md`. It is a gap because there is no way to schedule work across a reboot from
 * a normal app without leaving a boot receiver installed as root, which is a larger and more
 * permanent intrusion than the feature is worth.
 *
 * All calls except [state] are BLOCKING — call from Dispatchers.IO.
 */
object VendorLauncher {

    private const val TAG = "VendorLauncher"

    /** The stock launcher package (CAR_API §7). */
    const val PACKAGE = "com.szchoiceway.customerui"

    /**
     * How long the armed rollback waits. Long enough to boot our launcher, look at the home
     * screen, open a couple of screens and decide; short enough that a user who walked away from
     * a wedged unit gets it back before they need the car.
     */
    const val ROLLBACK_WINDOW_SEC = 180

    /**
     * Touching this file tells the armed rollback to stand down. A file rather than a pid because
     * the detached shell has no stable pid we could record before it exists, and a stale pid kill
     * would eventually hit an unrelated process.
     */
    private const val KEEP_FILE = "/data/local/tmp/carlauncher_keep_sole_home"

    enum class State {
        /** Installed and enabled — sole-HOME mode is off. */
        ENABLED,

        /** Installed but disabled for this user — sole-HOME mode is on. */
        DISABLED,

        /** Not on this unit at all (already uninstalled, or a different vendor build). */
        ABSENT,

        /** The package manager would not say. Treated as "do not offer the control". */
        UNKNOWN,
    }

    /** Non-blocking. */
    fun state(context: Context): State {
        val setting = runCatching {
            context.applicationContext.packageManager.getApplicationEnabledSetting(PACKAGE)
        }.getOrElse {
            // getApplicationEnabledSetting throws IllegalArgumentException for a package the
            // system does not know, which is the only reliable "absent" signal we get: a
            // disabled-user package still resolves here but not through getApplicationInfo.
            return if (it is IllegalArgumentException) State.ABSENT else State.UNKNOWN
        }

        return when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> State.DISABLED

            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            -> State.ENABLED

            else -> State.UNKNOWN
        }
    }

    /**
     * Arm the rollback, then disable the vendor launcher. `disable-user` rather than `uninstall`
     * so the APK stays on the unit and re-enabling is one command with nothing to reinstall.
     *
     * Everything interpolated into the shell text below is a compile-time constant of this file —
     * no caller value reaches it — and the one nested script is single-quoted through
     * [RootShell.quote] anyway.
     *
     * @return true if the package manager reported the disable succeeded.
     */
    fun disable(): Boolean {
        val rollback = "sleep $ROLLBACK_WINDOW_SEC; " +
            "if [ ! -f $KEEP_FILE ]; then pm enable $PACKAGE; fi; rm -f $KEEP_FILE"

        // Order matters: arm first. Disabling first would leave a window, however short, in which
        // a crash strands the unit with no rollback pending at all.
        val res = RootShell.exec(
            "rm -f $KEEP_FILE",
            "(nohup sh -c ${RootShell.quote(rollback)} >/dev/null 2>&1 &)",
            "pm disable-user --user 0 $PACKAGE",
        )

        if (!res.ok) {
            Log.w(TAG, "disable failed: $res")
        }
        return res.ok
    }

    /** Stand the armed rollback down — the user has confirmed the unit is fine. */
    fun keepDisabled(): Boolean = RootShell.exec("touch $KEEP_FILE").ok

    /** Re-enable immediately, and clear the keep-file so a later disable re-arms cleanly. */
    fun enable(): Boolean {
        val res = RootShell.exec("pm enable $PACKAGE", "rm -f $KEEP_FILE")
        if (!res.ok) {
            Log.w(TAG, "enable failed: $res")
        }
        return res.ok
    }
}
