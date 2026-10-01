package com.ripostelabs.carlauncher.ui.nav

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ripostelabs.carlauncher.carlib.McuOwner
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.data.ClockStyle
import com.ripostelabs.carlauncher.data.IdleClock
import com.ripostelabs.carlauncher.data.SaverInputs
import com.ripostelabs.carlauncher.data.ScreensaverPolicy
import com.ripostelabs.carlauncher.ui.ClockFace
import com.ripostelabs.carlauncher.ui.rememberNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * RAV4-201 — the launcher's screensaver: a dim clock over a black panel after the Power & sleep
 * "Screensaver after" idle time. Riposte OS 0.2 has no vendor screensaver; on the vendor image
 * its own one runs and this stays off ([start] is not called).
 *
 * <pre>
 *   touch anywhere ──▶ watch window (1 px, ACTION_OUTSIDE) ──┐
 *   wheel / panel key, volume, launcher input ───────────────┼─▶ IdleClock
 *                                                            │
 *   every second: ScreensaverPolicy(idle, timeout, ACC, reverse, call, moving)
 *        show ──▶ full-screen overlay: clock + date, drifting each minute
 *        hide ◀── touch on it, a key, or any blocker arriving
 * </pre>
 *
 * Every overlay the driver must see (reverse camera, call card, volume bar) is also a
 * TYPE_APPLICATION_OVERLAY window, and each one's trigger is a blocker or an activity here, so
 * the saver is gone before they show rather than stacked over them.
 */
class ScreensaverWindow(private val context: Context, private val idle: IdleClock) {

    private companion object {
        const val TAG = "Screensaver"
        const val OVERLAY_APPOP = "SYSTEM_ALERT_WINDOW"
        const val TICK_MS = 1_000L
        const val DRIFT_DP = 120
        const val FACE_ALPHA = 0.55f
        const val DIGITAL_SP = 120
        const val ANALOG_DP = 260
        const val DATE_SP = 28
        const val WATCH_PX = 1
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val host = Host()
    private val main = Handler(Looper.getMainLooper())
    private var saver: ComposeView? = null
    private var watch: View? = null
    private var loop: Job? = null
    private var style by mutableStateOf(ClockStyle.DIGITAL)

    /** Sample [inputs] each second and show or hide the saver. Call once, on the main thread. */
    fun start(scope: CoroutineScope, inputs: () -> SaverInputs) {
        if (loop != null) {
            return
        }
        addWatch()
        loop = scope.launch {
            while (isActive) {
                // Sample after the first tick, not inside start(): the caller may still be
                // wiring what inputs() reads.
                delay(TICK_MS)
                val want = ScreensaverPolicy.shouldShow(inputs().copy(idleMs = idle.idleMs()))
                if (want && saver == null) {
                    show()
                }
                if (!want && saver != null) {
                    hide()
                }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        hide()
        watch?.let { v -> runCatching { windowManager.removeViewImmediate(v) } }
        watch = null
    }

    /** The home clock's face, reused; OFF there still shows digits here. */
    fun update(face: ClockStyle) {
        style = if (face == ClockStyle.OFF) ClockStyle.DIGITAL else face
    }

    /** User activity from outside the windows (keys, volume, launcher input): wake and reset. Any thread. */
    fun activity() {
        idle.touch()
        main.post { hide() }
    }

    private fun show() {
        if (!ensureOverlayAllowed()) {
            return
        }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent { Saver() }
            // Any touch ends it; the press does not reach the app underneath.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    activity()
                }
                true
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            title = TAG
            fitInsetsTypes = 0
        }

        try {
            windowManager.addView(v, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot add the screensaver window", e)
            return
        }
        host.resume()
        saver = v
    }

    private fun hide() {
        val v = saver ?: return
        host.pause()
        runCatching { windowManager.removeViewImmediate(v) }
        saver = null
    }

    /**
     * A 1 px window that sees every touch on the panel as ACTION_OUTSIDE, whoever's window it
     * lands in. That is the idle clock's only view of touches in other apps.
     */
    private fun addWatch() {
        if (!ensureOverlayAllowed()) {
            return
        }
        val v = View(context).apply {
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    idle.touch()
                }
                false
            }
        }
        val params = WindowManager.LayoutParams(
            WATCH_PX,
            WATCH_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT,
        ).apply {
            title = "$TAG.watch"
        }
        try {
            windowManager.addView(v, params)
            watch = v
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot add the touch watch window", e)
        }
    }

    @Composable
    private fun Saver() {
        val now by rememberNow()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val (dx, dy) = ScreensaverPolicy.drift(minute, DRIFT_DP)
        val date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now.time)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .semantics { contentDescription = "Screensaver. Touch to wake." },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .offset(dx.dp, dy.dp)
                    .alpha(FACE_ALPHA),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ClockFace(
                    style = style,
                    now = now,
                    digitalSize = DIGITAL_SP,
                    analogSize = ANALOG_DP.dp,
                    color = Color.White,
                )
                Text(text = date, color = Color.White, fontSize = DATE_SP.sp)
            }
        }
    }

    /** Same grant as the nav bar: the appop is off for a normal install, root turns it on once. */
    private fun ensureOverlayAllowed(): Boolean {
        if (Settings.canDrawOverlays(context)) {
            return true
        }

        val r = RootShell.exec("appops set ${context.packageName} $OVERLAY_APPOP allow")
        if (!r.ok) {
            Log.w(TAG, "no overlay permission and no root to grant it")
        }
        return Settings.canDrawOverlays(context)
    }

    /** A ComposeView outside an Activity needs its own lifecycle and saved-state owners. */
    private class Host : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        init {
            savedState.performRestore(Bundle())
            registry.currentState = Lifecycle.State.CREATED
        }

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun pause() {
            registry.currentState = Lifecycle.State.CREATED
        }
    }
}

/** RAV4-201: every MCU key (panel, wheel, raw) is activity for the screensaver. */
class ScreensaverKeys(private val touch: () -> Unit) : McuOwner.Listener {
    override fun onKey(key: Int) = touch()

    override fun onPanelKey(key: McuOwnerProtocol.PanelKey) = touch()

    override fun onWheelKey(key: McuOwnerProtocol.WheelKey) = touch()
}
