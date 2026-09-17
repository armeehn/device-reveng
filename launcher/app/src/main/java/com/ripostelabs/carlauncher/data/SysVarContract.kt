package com.ripostelabs.carlauncher.data

import android.net.Uri

/**
 * SysVarContract — the launcher's stand-in for eventcenter's SysVar store on Riposte OS 0.2.
 *
 * Same table shape as `content://com.szchoiceway.eventcenter.SysVarProvider/SysVar` (one row per
 * key, columns `keyname` / `keyvalue`, selection `keyname=?`) so a suite reader keeps its query
 * and only adds a second authority to try. Rows come from [com.ripostelabs.carlauncher.carlib.SysVarMirror],
 * so only what the owner path recomputes is served; today that is `Sys_CurBreakSate` (RAV4-98).
 *
 * Read-only, and never the vendor's authority: claiming that would make the launcher refuse to
 * install next to eventcenter on stock. ApplicationId-scoped for the same reason as the theme
 * provider; suite apps query [AUTHORITY], the release install.
 */
object SysVarContract {
    const val AUTHORITY = "com.ripostelabs.carlauncher.sysvar"

    const val PATH = "SysVar"

    const val COL_KEYNAME = "keyname"
    const val COL_KEYVALUE = "keyvalue"

    val COLUMNS: Array<String> = arrayOf(COL_KEYNAME, COL_KEYVALUE)

    /** The one selection the vendor's readers use, and the only one served. */
    const val SELECT_BY_KEY = "$COL_KEYNAME=?"

    // Lazy for the same reason as ThemeContract: `Uri` has no JVM implementation.
    val TABLE_URI: Uri by lazy { tableUri(AUTHORITY) }

    fun tableUri(authority: String): Uri = Uri.parse("content://$authority/$PATH")

    /** The authority this install serves on, given its applicationId. */
    fun authorityFor(applicationId: String): String = "$applicationId.sysvar"
}
