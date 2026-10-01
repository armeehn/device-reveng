package com.ripostelabs.carlauncher.data

import android.content.Context
import com.ripostelabs.carlauncher.carlib.DataBudget
import com.ripostelabs.carlauncher.carlib.NoisePlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UplinkPrefs: the uplink's own small store, apart from [SettingsStore] so this feature never
 * contends with the main settings file. Holds the owner's two choices (automatic capture on or
 * off, the monthly tether budget), the budget's usage, and what Settings shows as status.
 */
class UplinkPrefs(context: Context) : DataBudget.Store {

    /** What Settings shows. Times are wall-clock ms; 0 = never. */
    data class Status(
        val autoCapture: NoisePlan.Switch,
        val budgetMb: Long,
        val usedBytes: Long,
        val queuedBytes: Long,
        val lastUploadMs: Long,
        val uploadedFiles: Long,
        val keptSeconds: Double,
        val droppedSpeechSeconds: Double,
        val state: String,
    )

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(read())
    val status: StateFlow<Status> = _status.asStateFlow()

    override var month: String
        get() = prefs.getString(KEY_MONTH, "") ?: ""
        set(value) = edit { putString(KEY_MONTH, value) }

    override var usedBytes: Long
        get() = prefs.getLong(KEY_USED, 0)
        set(value) = edit { putLong(KEY_USED, value) }

    val autoCapture: NoisePlan.Switch
        get() = if (prefs.getBoolean(KEY_AUTO, true)) NoisePlan.Switch.ON else NoisePlan.Switch.OFF

    val budgetBytes: Long
        get() = prefs.getLong(KEY_BUDGET_MB, DataBudget.DEFAULT_LIMIT_MB) * MB

    fun setAutoCapture(switch: NoisePlan.Switch) = edit { putBoolean(KEY_AUTO, switch == NoisePlan.Switch.ON) }

    fun setBudgetMb(mb: Long) = edit { putLong(KEY_BUDGET_MB, mb) }

    fun onUploaded(nowMs: Long) = edit {
        putLong(KEY_LAST_UPLOAD, nowMs)
        putLong(KEY_FILES, prefs.getLong(KEY_FILES, 0) + 1)
    }

    fun addKept(seconds: Double) = edit { putFloat(KEY_KEPT, (prefs.getFloat(KEY_KEPT, 0f) + seconds).toFloat()) }

    fun addDropped(seconds: Double) = edit { putFloat(KEY_DROPPED, (prefs.getFloat(KEY_DROPPED, 0f) + seconds).toFloat()) }

    fun setQueued(bytes: Long) = edit { putLong(KEY_QUEUED, bytes) }

    fun setState(text: String) = edit { putString(KEY_STATE, text) }

    /** Log rotations already copied off the ring, by size and mtime (names shift on rotation). */
    fun logSeen(key: String): Boolean = prefs.getStringSet(KEY_LOGS, emptySet())!!.contains(key)

    fun markLog(key: String) = edit {
        val kept = (prefs.getStringSet(KEY_LOGS, emptySet())!!.toList() + key).takeLast(MAX_LOG_KEYS)
        putStringSet(KEY_LOGS, kept.toSet())
    }

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _status.value = read()
    }

    private fun read() = Status(
        autoCapture = if (prefs.getBoolean(KEY_AUTO, true)) NoisePlan.Switch.ON else NoisePlan.Switch.OFF,
        budgetMb = prefs.getLong(KEY_BUDGET_MB, DataBudget.DEFAULT_LIMIT_MB),
        usedBytes = prefs.getLong(KEY_USED, 0),
        queuedBytes = prefs.getLong(KEY_QUEUED, 0),
        lastUploadMs = prefs.getLong(KEY_LAST_UPLOAD, 0),
        uploadedFiles = prefs.getLong(KEY_FILES, 0),
        keptSeconds = prefs.getFloat(KEY_KEPT, 0f).toDouble(),
        droppedSpeechSeconds = prefs.getFloat(KEY_DROPPED, 0f).toDouble(),
        state = prefs.getString(KEY_STATE, "Starting") ?: "Starting",
    )

    companion object {
        private const val FILE = "uplink"
        private const val KEY_AUTO = "auto_capture"
        private const val KEY_BUDGET_MB = "tether_budget_mb"
        private const val KEY_MONTH = "budget_month"
        private const val KEY_USED = "budget_used_bytes"
        private const val KEY_QUEUED = "queued_bytes"
        private const val KEY_LAST_UPLOAD = "last_upload_ms"
        private const val KEY_FILES = "uploaded_files"
        private const val KEY_KEPT = "kept_seconds"
        private const val KEY_DROPPED = "dropped_speech_seconds"
        private const val KEY_STATE = "state"
        private const val KEY_LOGS = "logs_seen"
        private const val MAX_LOG_KEYS = 200
        const val MB = 1024L * 1024

        /** Budget choices offered in Settings, in MB per month. 0 turns tethered uploads off. */
        val BUDGET_CHOICES = listOf(0L, 50L, 100L, 200L, 500L, 1000L)

        @Volatile
        private var instance: UplinkPrefs? = null

        /** One store per process: the service writes, Settings reads, both see every change. */
        fun get(context: Context): UplinkPrefs =
            instance ?: synchronized(this) { instance ?: UplinkPrefs(context.applicationContext).also { instance = it } }
    }
}
