package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.util.Log

/**
 * v2.9 — ownership of the vendor's bottom navigation bar, at the SysVar level.
 *
 * On a stock skin the "vendor nav bar" is Android's own navigation bar, which the gateway sizes
 * and enables from SysVar at boot and whenever the key changes
 * (`eventcenter/utils/SystemUtils.java:90-132`, re-run live from `changeSetup`,
 * `EventService.java:5125,5172`):
 *
 *     Sys_Landscape = 1 ──┬── Sys_Customer_NaviBar_Height_Key > 0 ──▶ show, height = value px
 *                         └── Sys_Customer_NaviBar_Height_Key = 0 ──▶ persist.sys.show_navigationbar = 0
 *     Sys_Landscape = 0 ──▶ both bars forced on, heights -1 (the key is ignored)
 *
 * Turning it off there is the *persistent* half of owning the screen: it is config the vendor
 * stack reads for itself, so it survives a reboot and needs no re-assertion.
 *
 * ### The status bar cannot be hidden this way
 *
 * `persist.sys.show_statusbar` is set to "1" in every branch of that code, and
 * `Sys_customer_statusbar` is written by the gateway, never read as a switch. No SysVar hides the
 * status bar; that stays a runtime SystemUI matter on a different stack.
 *
 * ### What the earlier guesses were
 *
 * `SYS_SHOW_TOOL_NAVI_BAR_WND` is a SystemProperties key the gateway *writes* as a state mirror
 * for its floating tool windows on skins 108/126/127 (`EventService.java:14428`); nothing reads it.
 * `Sys_Statusbar_Icon_Config_Key` has no reader in any vendor package. Both are gone.
 *
 * ### Safety
 *
 *  1. Opt-in — nothing here runs unless the user turns the setting on.
 *  2. [setHidden] only writes a key that is **already present** in the live table and only when
 *     `Sys_Landscape` is 1, the branch in which the gateway honours it.
 *  3. The height the key held before the first hide is recorded and written back verbatim on
 *     un-hide, so "off" restores the vendor's own bar rather than a size we invented.
 *
 * Writes prefer `IEventService.changeSetup` on the bound [CarService] so the gateway re-runs its
 * geometry immediately; unbound they fall through to [SysVar] → [RootShell]. Either way they are
 * BLOCKING — call from Dispatchers.IO.
 */
class VendorChrome(context: Context, private val carService: CarService? = null) {

    companion object {
        private const val TAG = "VendorChrome"

        /** Bottom bar height in px; 0 = no bar (`SysProviderOpt.java:283`). */
        const val KEY_NAVIBAR_HEIGHT = "Sys_Customer_NaviBar_Height_Key"

        /** The gateway honours [KEY_NAVIBAR_HEIGHT] only in this branch (`SysProviderOpt.java:335`). */
        const val KEY_LANDSCAPE = "Sys_Landscape"
        private const val LANDSCAPE_ON = "1"

        /** "No bottom bar", the vendor factory page's own option (`FactorySetFragment.java:170-215`). */
        private const val HIDDEN_VALUE = "0"

        /**
         * Where the pre-hide vendor value is kept. This belongs to the driver that overwrote it,
         * not to the app: the knowledge "what was there before we clobbered it" is only
         * meaningful next to the code that did the clobbering.
         */
        private const val PREFS = "carlib_vendor_chrome"
        private const val BACKUP_PREFIX = "backup."
        private const val KEY_HIDDEN = "hidden"
    }

    /** Whether this unit can act on the toggle at all, and if not, why. */
    enum class Support { READY, KEY_ABSENT, NOT_LANDSCAPE }

    private val appContext = context.applicationContext
    private val sysVar = SysVar(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Our own record of what we last applied. Cheap, and readable on the main thread. */
    fun isHidden(): Boolean = prefs.getBoolean(KEY_HIDDEN, false)

    /** Probe the two gating facts on this unit. BLOCKING. */
    fun support(): Support {
        if (sysVar.getString(KEY_NAVIBAR_HEIGHT) == null) {
            return Support.KEY_ABSENT
        }

        if (sysVar.getString(KEY_LANDSCAPE)?.trim() != LANDSCAPE_ON) {
            return Support.NOT_LANDSCAPE
        }

        return Support.READY
    }

    /**
     * Hide or restore the vendor nav bar.
     *
     * @return true if the write landed. False means the unit cannot act ([support]) or the write
     *   was refused, which on a rooted unit means the SysVar write path itself failed.
     */
    fun setHidden(hidden: Boolean): Boolean {
        val support = support()
        if (support != Support.READY) {
            Log.w(TAG, "cannot change the vendor nav bar: $support")
            return false
        }

        if (hidden) {
            backupOnce()
        }

        val value = if (hidden) HIDDEN_VALUE else restoreValue()
        if (value == null) {
            Log.w(TAG, "no recorded height to restore")
            return false
        }

        if (!write(value)) {
            Log.w(TAG, "chrome write failed: $KEY_NAVIBAR_HEIGHT=$value")
            return false
        }

        prefs.edit().putBoolean(KEY_HIDDEN, hidden).apply()
        return true
    }

    /** Gateway first so `initNaviAndStatusBarHeight` re-runs live; provider when unbound. */
    private fun write(value: String): Boolean {
        if (carService?.changeSetup(KEY_NAVIBAR_HEIGHT, value) == true) {
            return true
        }

        return sysVar.putString(KEY_NAVIBAR_HEIGHT, value)
    }

    /**
     * Snapshot the vendor's height the first time we hide, and never again. Re-snapshotting on a
     * second hide would capture our own [HIDDEN_VALUE] and make the restore a no-op — the exact
     * way this kind of save/restore usually rots.
     */
    private fun backupOnce() {
        val slot = BACKUP_PREFIX + KEY_NAVIBAR_HEIGHT
        if (prefs.contains(slot)) {
            return
        }

        val current = sysVar.getString(KEY_NAVIBAR_HEIGHT) ?: return
        prefs.edit().putString(slot, current).apply()
    }

    /** null when we never saw the key before hiding, in which case we leave it alone. */
    private fun restoreValue(): String? = prefs.getString(BACKUP_PREFIX + KEY_NAVIBAR_HEIGHT, null)
}
