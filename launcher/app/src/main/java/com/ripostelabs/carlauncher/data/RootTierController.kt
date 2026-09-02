package com.ripostelabs.carlauncher.data

import android.content.Context
import android.os.SystemClock
import com.ripostelabs.carlauncher.carlib.VendorChrome
import com.ripostelabs.carlauncher.carlib.VendorLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.9 — the settings screen's view of the root tier.
 *
 * Sits where [CarSettingsController] sits, and for the same reason: the screens are Compose, the
 * work underneath is blocking root I/O, and the boundary between them belongs in one place. The UI
 * reads StateFlows and calls plain methods; it never learns that a shell exists.
 *
 * Nothing here runs at construction except one SharedPreferences read. The two probes that are not
 * cheap — the vendor-launcher package-manager query and the SysVar key sweep — both run inside
 * [refresh] on Dispatchers.IO, so wiring it into MainActivity costs a launch nothing.
 */
class RootTierController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val chrome = VendorChrome(appContext)

    private val _chromeHidden = MutableStateFlow(chrome.isHidden())
    /** True while we are suppressing the vendor status/nav bar. */
    val chromeHidden: StateFlow<Boolean> = _chromeHidden.asStateFlow()

    private val _chromeKeysPresent = MutableStateFlow<List<String>?>(null)
    /**
     * Which of the two vendor chrome keys this unit actually has; null until probed, empty when
     * neither exists. Surfaced because both keynames are GUESSED (see [VendorChrome]) and "the
     * toggle did nothing" should be visible, not mysterious.
     */
    val chromeKeysPresent: StateFlow<List<String>?> = _chromeKeysPresent.asStateFlow()

    private val _vendorLauncher = MutableStateFlow(VendorLauncher.State.UNKNOWN)
    /** UNKNOWN until [refresh] has probed — which is also how the UI already reads "no control". */
    val vendorLauncher: StateFlow<VendorLauncher.State> = _vendorLauncher.asStateFlow()

    private val _rollbackDeadline = MutableStateFlow<Long?>(null)
    /**
     * [SystemClock.elapsedRealtime] at which the armed rollback re-enables the vendor launcher, or
     * null when nothing is armed. Elapsed-realtime rather than wall clock so a time-zone or NTP
     * correction — both of which a head unit does on its own schedule — cannot move the deadline.
     *
     * This is our *belief* about the rollback, not the rollback itself. The real one is a detached
     * root shell that outlives this process; losing this value (a crash, a restart) loses the
     * countdown display, never the recovery.
     */
    val rollbackDeadline: StateFlow<Long?> = _rollbackDeadline.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        // VendorLauncher.state() is a getApplicationEnabledSetting binder call. refresh() is called
        // from init, i.e. from the activity's onCreate, so it belongs off the main thread with the
        // SysVar sweep rather than on the launcher's cold-start path.
        scope.launch {
            withContext(Dispatchers.IO) {
                _chromeKeysPresent.value = chrome.presentKeys()
                _vendorLauncher.value = VendorLauncher.state(appContext)
            }
        }
    }

    // ---- Vendor chrome ------------------------------------------------------

    fun setChromeHidden(hidden: Boolean) {
        // Optimistic, matching CarSettingsController: the switch moves on touch and rolls back if
        // the write is refused, rather than sitting dead for the length of a root round-trip.
        _chromeHidden.value = hidden
        scope.launch {
            val ok = withContext(Dispatchers.IO) { chrome.setHidden(hidden) }
            if (!ok) {
                _chromeHidden.value = chrome.isHidden()
            }
        }
    }

    // ---- Sole-HOME mode -----------------------------------------------------

    /** Disable the vendor launcher with the rollback armed. See [VendorLauncher]. */
    fun disableVendorLauncher() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { VendorLauncher.disable() }
            if (ok) {
                _rollbackDeadline.value = SystemClock.elapsedRealtime() +
                    VendorLauncher.ROLLBACK_WINDOW_SEC * MILLIS_PER_SECOND
            }
            refresh()
        }
    }

    /** "It works, keep it off" — stands the armed rollback down. */
    fun keepVendorLauncherDisabled() {
        scope.launch {
            withContext(Dispatchers.IO) { VendorLauncher.keepDisabled() }
            _rollbackDeadline.value = null
        }
    }

    /** Put the vendor launcher back now, without waiting for the rollback. */
    fun enableVendorLauncher() {
        scope.launch {
            withContext(Dispatchers.IO) { VendorLauncher.enable() }
            _rollbackDeadline.value = null
            refresh()
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
