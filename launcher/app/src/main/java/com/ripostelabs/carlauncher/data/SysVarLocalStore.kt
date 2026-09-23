package com.ripostelabs.carlauncher.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The launcher's own SysVar rows for Riposte OS 0.2, where the vendor gateway is unbound and
 * `content://com.szchoiceway.eventcenter.SysVarProvider` does not exist. Rows keep their vendor
 * names so the settings screens and the suite's readers need no translation.
 *
 *     Settings screen ──▶ CarSettingsController ──LOCAL──▶ put() ──▶ rows (SharedPreferences)
 *                                                            └──▶ onRow: SysVarMirrorProvider.publish,
 *                                                                        ReverseCameraDecoder.apply
 *     boot ──▶ republish() ──▶ onRow for every saved row
 */
class SysVarLocalStore(
    private val rows: Rows,
    private val onRow: (key: String, value: String) -> Unit = { _, _ -> },
) {

    /** Where the rows live: SharedPreferences on the unit, a map in tests. */
    interface Rows {
        fun readAll(): Map<String, String>

        /** True once the row is on disk. */
        fun write(key: String, value: String): Boolean
    }

    fun readAll(): Map<String, String> = rows.readAll()

    /** Persist one row and announce it; false when the write did not stick (nothing announced). */
    fun put(key: String, value: String): Boolean {
        if (!rows.write(key, value)) {
            return false
        }

        onRow(key, value)
        return true
    }

    /** Announce every saved row again, so readers and side effects see them after a restart. */
    fun republish() {
        for ((key, value) in rows.readAll()) {
            onRow(key, value)
        }
    }

    companion object {
        private const val PREFS = "sysvar_local"

        /** The rows file this install keeps. */
        fun prefs(context: Context): Rows =
            PrefsRows(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

/** SharedPreferences as rows; committed synchronously because the caller is already off-main. */
private class PrefsRows(private val prefs: SharedPreferences) : SysVarLocalStore.Rows {

    override fun readAll(): Map<String, String> =
        prefs.all.entries.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    override fun write(key: String, value: String): Boolean =
        prefs.edit().putString(key, value).commit()
}
