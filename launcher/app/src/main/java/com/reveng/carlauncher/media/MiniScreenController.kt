package com.reveng.carlauncher.media

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.reveng.carlauncher.carlib.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the video mini screen is doing right now (drives [com.reveng.carlauncher.ui.VideoMiniCard]). */
sealed interface MiniScreenState {
    data object Hidden : MiniScreenState
    /** The video app was launched as a freeform window over the home-card slot. */
    data class Active(val packageName: String) : MiniScreenState
    data class Failed(val reason: String) : MiniScreenState
}

/**
 * v4.1 — the video mini screen: floats the playing video app as a small freeform window
 * positioned exactly over the reserved slot in the home layout's media card.
 *
 * **Why freeform and not a surface.** A launcher cannot host another app's video surface:
 * TaskView/ActivityView need the platform signature (unobtainable on this unit — see the
 * project notes), and MediaProjection can only capture our own display, where the video app
 * would be paused. What an ordinary app *can* do is start an activity with public
 * [ActivityOptions.setLaunchBounds]; when freeform windowing is enabled system-wide, that
 * launches the target as a small floating window at those bounds. Root is used exactly once,
 * to flip `enable_freeform_support` — the launch itself is public API.
 *
 * **Dismissal** injects HOME (`input keyevent 3`): on Android 13 that sends freeform tasks to
 * recents. The injected HOME loops back into MainActivity.onNewIntent as a real HOME intent,
 * so [consumeHomeInjection] lets the activity tell our own injection apart from the driver's
 * press and skip the go-home navigation reset it would otherwise do.
 *
 * On-device caveat (untested on the GT6 vendor build): whether the toggled setting takes
 * effect without a reboot, and whether the vendor SystemUI minimizes freeform on HOME, are
 * AOSP behaviors this ROM is expected to keep but has not yet been observed doing. [state]
 * surfaces failures instead of guessing.
 */
class MiniScreenController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "MiniScreen"
        private const val FREEFORM_SETTING = "enable_freeform_support"
        /** How long after our HOME injection an incoming HOME intent is treated as ours. */
        private const val HOME_INJECTION_WINDOW_MS = 3_000L
    }

    private val _state = MutableStateFlow<MiniScreenState>(MiniScreenState.Hidden)
    val state: StateFlow<MiniScreenState> = _state.asStateFlow()

    /**
     * Package whose mini window the driver explicitly closed. Held as state so the home screen
     * recomposes when it changes: auto-show stays off for this session until playback moves to
     * another app or stops ([clearUserClosed]).
     */
    private val _userClosed = MutableStateFlow<String?>(null)
    val userClosed: StateFlow<String?> = _userClosed.asStateFlow()

    @Volatile
    private var suppressHomeUntil = 0L

    /**
     * Launch [packageName] as a freeform window at [boundsPx] (screen pixels — the home layout
     * is full-screen, so window and screen coordinates coincide). No-op while already active
     * for the same package: layout settling re-reports bounds, and relaunching on every report
     * would make the window flicker.
     */
    fun show(packageName: String, boundsPx: Rect) {
        val cur = _state.value
        if (cur is MiniScreenState.Active && cur.packageName == packageName) return
        scope.launch(Dispatchers.IO) {
            if (!ensureFreeformSupported()) {
                // Refusing is the safe failure: launching without freeform would open the video
                // app FULL SCREEN over the home the driver is looking at, from no visible action.
                Log.w(TAG, "freeform unavailable; not launching $packageName")
                _state.value = MiniScreenState.Failed("Freeform windows unavailable")
                return@launch
            }
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                _state.value = MiniScreenState.Failed("App can't be launched")
                return@launch
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().apply { launchBounds = boundsPx }
            withContext(Dispatchers.Main) {
                runCatching { context.startActivity(intent, options.toBundle()) }
                    .onSuccess { _state.value = MiniScreenState.Active(packageName) }
                    .onFailure {
                        Log.w(TAG, "mini launch $packageName failed: ${it.message}")
                        _state.value = MiniScreenState.Failed("Couldn't open the video window")
                    }
            }
        }
    }

    /**
     * Hide the mini window. [userClosed] marks it as the driver's own choice so the home screen
     * doesn't immediately auto-show it again.
     */
    fun dismiss(userClosed: Boolean = false) {
        val cur = _state.value
        if (userClosed && cur is MiniScreenState.Active) _userClosed.value = cur.packageName
        _state.value = MiniScreenState.Hidden
        if (cur !is MiniScreenState.Active) return
        scope.launch(Dispatchers.IO) {
            // Arm the window only once the HOME really went in. Arming first meant that when the
            // injection failed — no root, Magisk denied — the window was still open and ate the
            // driver's next real HOME press, so Close then the wheel HOME key did nothing.
            val r = RootShell.exec("input keyevent 3")
            if (r.code != 0) {
                Log.w(TAG, "dismiss keyevent failed: ${r.err.joinToString()}")
                return@launch
            }
            suppressHomeUntil = SystemClock.elapsedRealtime() + HOME_INJECTION_WINDOW_MS
        }
    }

    /** Promote the mini window to a normal full-screen task (the card's expand button). */
    fun expand() {
        val cur = _state.value as? MiniScreenState.Active ?: return
        _state.value = MiniScreenState.Hidden
        scope.launch(Dispatchers.IO) {
            val cmp = context.packageManager
                .getLaunchIntentForPackage(cur.packageName)?.component
            val moved = cmp != null &&
                RootShell.exec(
                    "am start --windowingMode 1 -n ${RootShell.quote(cmp.flattenToShortString())}"
                ).code == 0
            if (!moved) {
                // Rootless fallback: a plain launch focuses the task; on some builds it stays
                // freeform, which still hands the driver the window they asked to grow.
                withContext(Dispatchers.Main) {
                    runCatching {
                        context.packageManager.getLaunchIntentForPackage(cur.packageName)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let(context::startActivity)
                    }
                }
            }
        }
    }

    /** Forget a user Close once its session ends, so the next video auto-shows again. */
    fun clearUserClosed() {
        _userClosed.value = null
    }

    /**
     * True when an incoming HOME intent is the echo of our own [dismiss] injection — the
     * activity should then keep its current screen instead of resetting to Home.
     */
    fun consumeHomeInjection(): Boolean {
        val ours = SystemClock.elapsedRealtime() < suppressHomeUntil
        if (ours) suppressHomeUntil = 0L
        return ours
    }

    /**
     * Freeform is supported when the ROM declares the feature or the development global is set;
     * otherwise try to set the global via root and re-read it. Blocking — call on IO.
     */
    private fun ensureFreeformSupported(): Boolean {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) {
            return true
        }
        val resolver = context.contentResolver
        if (Settings.Global.getInt(resolver, FREEFORM_SETTING, 0) == 1) return true
        val r = RootShell.exec("settings put global $FREEFORM_SETTING 1")
        Log.i(TAG, "enable $FREEFORM_SETTING -> code=${r.code}")
        return Settings.Global.getInt(resolver, FREEFORM_SETTING, 0) == 1
    }
}
