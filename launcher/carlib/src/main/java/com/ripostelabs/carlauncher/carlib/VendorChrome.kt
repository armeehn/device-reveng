package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.util.Log

/**
 * v2.9 — ownership of the vendor's own status/nav bar configuration, at the SysVar level.
 *
 * The gateway inflates its shared status bar and side/tool nav window from layouts that live in
 * `com.szchoiceway.customerui` (CUSTOMERUI_NOTES §3g) and decides what to show from SysVar
 * (CAR_API §6.3). Turning those off there is the *persistent* half of owning the screen: it is
 * config the vendor stack reads for itself, so it survives a reboot and needs no re-assertion.
 *
 * ### Scope, and what this deliberately is not
 *
 * This is not the Android SystemUI shade or the transient system status bar — those are runtime
 * state on a different stack, addressed separately. This class only writes two vendor keys, and
 * only ones that already exist in the live table.
 *
 * ### GUESSED, and why it is still safe to ship
 *
 *  * `Sys_Statusbar_Icon_Config_Key` is an *icon config*, not a flag: CAR_API §6.3 pairs it with
 *    `EventUtils.DEFAULT_ICON_CONFIG`, a list of icon ids. That "empty means draw nothing" equals
 *    "hidden" is **GUESSED** — plausible, never observed.
 *  * `SYS_SHOW_TOOL_NAVI_BAR_WND` is quoted in CAR_API §6.3 in the SCREAMING form the vendor uses
 *    for *constant names*, and for most keys the constant and the stored keyname differ
 *    (`SYS_BACKCAR_DISPLAY_RADAR_KEY` stores as `Sys_BackCar_Display_Radar_Key`). The actual
 *    keyname is therefore **GUESSED** too.
 *
 * Three things keep that honest rather than reckless:
 *
 *  1. It is opt-in — nothing here runs unless the user turns the setting on.
 *  2. [setHidden] only writes a key that is **already present** in the live table, so a wrong guess
 *     changes nothing instead of inserting junk rows into the vehicle's config store.
 *  3. The value each key held before the first hide is recorded and written back verbatim on
 *     un-hide, so "off" restores the vendor's own configuration rather than a value we invented.
 *
 * Writes are BLOCKING (they go through [SysVar] → [RootShell]) — call from Dispatchers.IO.
 */
class VendorChrome(context: Context) {

    companion object {
        private const val TAG = "VendorChrome"

        /** Which icons the vendor status bar draws (CAR_API §6.3). */
        const val KEY_STATUSBAR_ICON_CONFIG = "Sys_Statusbar_Icon_Config_Key"

        /** Whether the vendor tool/nav bar window is shown. Keyname GUESSED — see class KDoc. */
        const val KEY_SHOW_NAVI_BAR = "SYS_SHOW_TOOL_NAVI_BAR_WND"

        /** What we write to hide. GUESSED for both keys — see class KDoc. */
        private const val HIDDEN_VALUE = "0"

        private val KEYS = arrayOf(KEY_STATUSBAR_ICON_CONFIG, KEY_SHOW_NAVI_BAR)

        /**
         * Where the pre-hide vendor values are kept. This belongs to the driver that overwrote
         * them, not to the app: the knowledge "what was there before we clobbered it" is only
         * meaningful next to the code that did the clobbering.
         */
        private const val PREFS = "carlib_vendor_chrome"
        private const val BACKUP_PREFIX = "backup."
        private const val KEY_HIDDEN = "hidden"
    }

    private val appContext = context.applicationContext
    private val sysVar = SysVar(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Our own record of what we last applied. Cheap, and readable on the main thread. */
    fun isHidden(): Boolean = prefs.getBoolean(KEY_HIDDEN, false)

    /**
     * Which of [KEYS] this unit actually has. Empty means neither guess exists here and the
     * setting cannot do anything — worth telling the user rather than silently doing nothing.
     * BLOCKING.
     */
    fun presentKeys(): List<String> = KEYS.filter { sysVar.getString(it) != null }

    /**
     * Hide or restore the vendor chrome.
     *
     * @return true if every key present on this unit was written. False means at least one write
     *   was refused, which on a rooted unit means the SysVar write path itself failed.
     */
    fun setHidden(hidden: Boolean): Boolean {
        val present = presentKeys()
        if (present.isEmpty()) {
            Log.w(TAG, "neither chrome key exists in the live SysVar table — nothing to do")
            return false
        }

        if (hidden) {
            backupOnce(present)
        }

        var allOk = true
        for (key in present) {
            val value = if (hidden) HIDDEN_VALUE else restoreValueFor(key) ?: continue
            if (!sysVar.putString(key, value)) {
                allOk = false
                Log.w(TAG, "chrome write failed: $key=$value")
            }
        }

        // Only record the new state if every write landed. A half-applied hide that we remembered
        // as "hidden" would report success to the UI and, worse, make the next un-hide look like a
        // no-op — so a partial failure leaves the recorded state alone and the caller rolls back.
        if (allOk) {
            prefs.edit().putBoolean(KEY_HIDDEN, hidden).apply()
        }

        return allOk
    }

    /**
     * Snapshot the vendor's values the first time we hide, and never again. Re-snapshotting on a
     * second hide would capture our own [HIDDEN_VALUE] and make the restore a no-op — the exact
     * way this kind of save/restore usually rots.
     */
    private fun backupOnce(keys: List<String>) {
        val editor = prefs.edit()
        for (key in keys) {
            if (prefs.contains(BACKUP_PREFIX + key)) {
                continue
            }
            editor.putString(BACKUP_PREFIX + key, sysVar.getString(key) ?: continue)
        }
        editor.apply()
    }

    /** null when we never saw this key before hiding, in which case we leave it alone. */
    private fun restoreValueFor(key: String): String? = prefs.getString(BACKUP_PREFIX + key, null)
}
