package com.ripostelabs.carlauncher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OsPrefs: the OS updater's store (OsUpdater). Where an update stands survives a launcher restart
 * and the reboot into the new slot, so the launcher that comes up next can tell what happened.
 * Settings > Updates reads [status].
 */
class OsPrefs(context: Context) {

    /** Where the update for [pendingVersion] stands. */
    enum class Phase { IDLE, APPLYING, SUSPENDED, REBOOTING }

    data class Status(val state: String, val lastResult: String)

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(read())
    val status: StateFlow<Status> = _status.asStateFlow()

    val phase: Phase
        get() = Phase.entries.firstOrNull { it.name == prefs.getString(KEY_PHASE, "") } ?: Phase.IDLE

    /** The version being applied or booted into; "" when none. */
    val pendingVersion: String
        get() = prefs.getString(KEY_VERSION, "") ?: ""

    /** That payload's OsPlan.key, refused if it fails. */
    val pendingKey: String
        get() = prefs.getString(KEY_KEY, "") ?: ""

    /** Payloads never to take again (OsPlan.key). */
    val refused: Set<String>
        get() = prefs.getStringSet(KEY_REFUSED, emptySet())!!.toSet()

    fun setPhase(phase: Phase, version: String = pendingVersion, key: String = pendingKey) = edit {
        putString(KEY_PHASE, phase.name)
        putString(KEY_VERSION, version)
        putString(KEY_KEY, key)
    }

    fun clear() = setPhase(Phase.IDLE, "", "")

    fun setState(text: String) = edit { putString(KEY_STATE, text) }

    fun setResult(text: String) = edit { putString(KEY_RESULT, text) }

    fun refuse(key: String) = edit {
        putStringSet(KEY_REFUSED, (refused.toList() + key).takeLast(MAX_REFUSED).toSet())
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        // commit, not apply: the next step may be a reboot.
        prefs.edit().apply(block).commit()
        _status.value = read()
    }

    private fun read() = Status(
        state = prefs.getString(KEY_STATE, "Starting") ?: "Starting",
        lastResult = prefs.getString(KEY_RESULT, "") ?: "",
    )

    companion object {
        private const val FILE = "os_ota"
        private const val KEY_PHASE = "phase"
        private const val KEY_VERSION = "version"
        private const val KEY_KEY = "key"
        private const val KEY_STATE = "state"
        private const val KEY_RESULT = "last_result"
        private const val KEY_REFUSED = "refused"
        private const val MAX_REFUSED = 20

        @Volatile
        private var instance: OsPrefs? = null

        fun get(context: Context): OsPrefs =
            instance ?: synchronized(this) { instance ?: OsPrefs(context.applicationContext).also { instance = it } }
    }
}
