package com.ripostelabs.carlauncher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OtaPrefs: the tailnet updater's small store (OtaUpdater). Holds the last manifest the estate
 * sent, when it was fetched, what is waiting, what happened last, and the versions the unit
 * will never take again (refused or rolled back). Settings > Updates reads [status].
 */
class OtaPrefs(context: Context) {

    /** What Settings shows. Times are wall-clock ms; 0 = never. */
    data class Status(
        val lastCheckMs: Long,
        val state: String,
        val available: String,
        val lastResult: String,
    )

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(read())
    val status: StateFlow<Status> = _status.asStateFlow()

    val lastCheckMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0)

    /** The last manifest text from the estate, or "" before the first check. */
    val manifest: String
        get() = prefs.getString(KEY_MANIFEST, "") ?: ""

    /** Builds never to install again (OtaPlan.key). */
    val refused: Set<String>
        get() = prefs.getStringSet(KEY_REFUSED, emptySet())!!.toSet()

    /** The launcher versionCode handed to the watch script; 0 when none is in flight. */
    val pendingLauncher: Long
        get() = prefs.getLong(KEY_PENDING, 0)

    /** That launcher build's OtaPlan.key, refused if the watch script rolls it back. */
    val pendingKey: String
        get() = prefs.getString(KEY_PENDING_KEY, "") ?: ""

    fun onChecked(nowMs: Long, manifestText: String) = edit {
        putLong(KEY_LAST_CHECK, nowMs)
        putString(KEY_MANIFEST, manifestText)
    }

    /** Settings' "Check now": the next tick asks the estate. */
    fun requestCheck() = edit { putLong(KEY_LAST_CHECK, 0) }

    fun setState(text: String) = edit { putString(KEY_STATE, text) }

    fun setAvailable(text: String) = edit { putString(KEY_AVAILABLE, text) }

    fun setResult(text: String) = edit { putString(KEY_RESULT, text) }

    fun setPendingLauncher(versionCode: Long, key: String) = edit {
        putLong(KEY_PENDING, versionCode)
        putString(KEY_PENDING_KEY, key)
    }

    fun refuse(key: String) = edit {
        val kept = (refused.toList() + key).takeLast(MAX_REFUSED)
        putStringSet(KEY_REFUSED, kept.toSet())
    }

    /** One more failed install of [key]; returns the count so far. */
    fun failed(key: String): Int {
        val n = prefs.getInt(KEY_FAIL + key, 0) + 1
        edit { putInt(KEY_FAIL + key, n) }
        return n
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _status.value = read()
    }

    private fun read() = Status(
        lastCheckMs = prefs.getLong(KEY_LAST_CHECK, 0),
        state = prefs.getString(KEY_STATE, "Starting") ?: "Starting",
        available = prefs.getString(KEY_AVAILABLE, "") ?: "",
        lastResult = prefs.getString(KEY_RESULT, "") ?: "",
    )

    companion object {
        private const val FILE = "ota"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_MANIFEST = "manifest"
        private const val KEY_STATE = "state"
        private const val KEY_AVAILABLE = "available"
        private const val KEY_RESULT = "last_result"
        private const val KEY_REFUSED = "refused"
        private const val KEY_PENDING = "pending_launcher"
        private const val KEY_PENDING_KEY = "pending_launcher_key"
        private const val KEY_FAIL = "fail:"
        private const val MAX_REFUSED = 100

        @Volatile
        private var instance: OtaPrefs? = null

        /** One store per process: the service writes, Settings reads, both see every change. */
        fun get(context: Context): OtaPrefs =
            instance ?: synchronized(this) { instance ?: OtaPrefs(context.applicationContext).also { instance = it } }
    }
}
