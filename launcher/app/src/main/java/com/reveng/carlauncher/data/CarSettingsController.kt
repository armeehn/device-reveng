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

    /**
     * Keys with an in-flight optimistic write, mapped to the value we are trying to persist.
     * A provider [refresh] must not clobber these (the control would visibly snap back), and a
     * failed write must only roll back if our value is still the current one. Confined to [scope].
     */
    private val pending = mutableMapOf<String, String>()

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
            // Preserve any optimistic-but-not-yet-persisted local edits: the fresh read wins
            // everywhere EXCEPT keys with a still-pending write, whose intended value we keep
            // overlaid so the control doesn't snap back and then jump forward again.
            if (map.isNotEmpty()) _snapshot.value = if (pending.isEmpty()) map else map + pending
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
        // v0.4.7 — every SysVar write funnels through here; the UI refusals are presentation
        // only. A refuse-listed key can brick the unit (see ProtectedSettingKeys), so refuse at
        // the choke point and surface the failure.
        if (ProtectedSettingKeys.isProtected(key)) {
            Log.w(TAG, "refused write to protected key: $key")
            _writeEvents.tryEmit(WriteEvent(key, value, ok = false))
            return
        }

        val previous = _snapshot.value[key]
        pending[key] = value
        // Optimistic: reflect immediately.
        _snapshot.value = _snapshot.value.toMutableMap().apply { put(key, value) }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { sysVar.putString(key, value) }.getOrDefault(false)
            }
            // Clear the pending marker only if it's still ours; a newer write may have superseded it.
            val superseded = pending[key] != value
            if (!superseded) pending.remove(key)
            if (!ok && !superseded) {
                // Roll back only if our optimistic value is still the current one — never clobber
                // a newer optimistic edit or fresher provider data written in the meantime.
                if (_snapshot.value[key] == value) {
                    _snapshot.value = _snapshot.value.toMutableMap().apply {
                        if (previous == null) remove(key) else put(key, previous)
                    }
                }
                Log.w(TAG, "SysVar write failed: $key=$value")
            } else if (!ok) {
                Log.w(TAG, "SysVar write failed (superseded, not rolled back): $key=$value")
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
