package com.reveng.carlauncher.data

import android.content.Context
import android.database.ContentObserver
import android.util.Log
import com.reveng.carlauncher.carlib.RootShell
import com.reveng.carlauncher.carlib.SysVar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.1 — the read/write bridge between the reskinned settings UI and the vendor SysVar store
 * (`content://com.szchoiceway.eventcenter.SysVarProvider/SysVar`, CAR_API §2).
 *
 * Every category screen observes [snapshot] (the whole SysVar table as a String→String map)
 * and calls the typed setters. Design points:
 *
 *  * **Reads** are the open ContentResolver query ([SysVar.readAll]); refreshed on construction,
 *    on demand, and whenever the provider notifies a change (via a [ContentObserver]).
 *  * **Writes** are optimistic: the in-memory [snapshot] updates immediately so the control moves
 *    the instant the user touches it, then the value is persisted off the main thread. Writing
 *    the vendor provider needs system uid or root, so [SysVar.putString] falls back to a root
 *    `content` shell (CAR_API §2.2). If the persist fails the optimistic value is rolled back and
 *    a message is emitted on [writeEvents].
 *  * **Root availability** is probed once and surfaced as [rootAvailable] so screens can warn that
 *    changes won't stick without root / a privileged install.
 *
 * All blocking work (ContentResolver + root shell) runs on [Dispatchers.IO]; the StateFlows are
 * updated on the caller's scope so Compose recomposes.
 */
class CarSettingsController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val sysVar = SysVar(appContext)

    private val _snapshot = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The whole SysVar table, latest known values. Empty until the first refresh completes. */
    val snapshot: StateFlow<Map<String, String>> = _snapshot.asStateFlow()

    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    /** null = not yet probed; true/false once known. Writes without root won't persist. */
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable.asStateFlow()

    /** One-shot write results for lightweight UI feedback (e.g. a snackbar/toast). */
    data class WriteEvent(val key: String, val value: String, val ok: Boolean)

    private val _writeEvents = MutableSharedFlow<WriteEvent>(extraBufferCapacity = 16)
    val writeEvents: SharedFlow<WriteEvent> = _writeEvents.asSharedFlow()

    private var observer: ContentObserver? = null

    init {
        refresh()
        scope.launch {
            _rootAvailable.value = withContext(Dispatchers.IO) {
                runCatching { RootShell.isRootAvailable() }.getOrDefault(false)
            }
        }
        observer = sysVar.observe {
            // A change we didn't make (vendor UI, CAN event) — reconcile our snapshot.
            refresh()
        }
    }

    /** Re-read the whole SysVar table off the main thread. */
    fun refresh() {
        scope.launch {
            val map = withContext(Dispatchers.IO) {
                runCatching { sysVar.readAll() }.getOrDefault(emptyMap())
            }
            // Preserve any optimistic-but-not-yet-refreshed local edits: the fresh read wins
            // only where it actually has the key, which after a successful write it will.
            if (map.isNotEmpty()) _snapshot.value = map
        }
    }

    fun release() {
        observer?.let { sysVar.unobserve(it) }
        observer = null
    }

    // ---- Typed reads off the snapshot --------------------------------------

    fun getString(key: String, def: String = ""): String = _snapshot.value[key] ?: def

    fun getInt(key: String, def: Int = 0): Int =
        _snapshot.value[key]?.trim()?.toIntOrNull() ?: def

    fun getBoolean(key: String, def: Boolean = false): Boolean {
        val v = _snapshot.value[key]?.trim() ?: return def
        return v == "1" || v.equals("true", ignoreCase = true)
    }

    /** True once we have any values at all (used to show a "reading…" state). */
    fun isLoaded(): Boolean = _snapshot.value.isNotEmpty()

    // ---- Optimistic writes -------------------------------------------------

    fun setString(key: String, value: String) {
        val previous = _snapshot.value[key]
        // Optimistic: reflect immediately.
        _snapshot.value = _snapshot.value.toMutableMap().apply { put(key, value) }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { sysVar.putString(key, value) }.getOrDefault(false)
            }
            if (!ok) {
                // Roll back the optimistic edit.
                _snapshot.value = _snapshot.value.toMutableMap().apply {
                    if (previous == null) remove(key) else put(key, previous)
                }
                Log.w(TAG, "SysVar write failed: $key=$value")
            }
            _writeEvents.tryEmit(WriteEvent(key, value, ok))
        }
    }

    fun setInt(key: String, value: Int) = setString(key, value.toString())

    fun setBoolean(key: String, value: Boolean) = setString(key, if (value) "1" else "0")

    private companion object {
        const val TAG = "CarSettingsController"
    }
}
