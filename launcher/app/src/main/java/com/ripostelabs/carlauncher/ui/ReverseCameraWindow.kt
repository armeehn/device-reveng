package com.ripostelabs.carlauncher.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ripostelabs.carlauncher.carlib.RadarState
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.ui.theme.BuiltInThemes
import com.ripostelabs.carlauncher.ui.theme.CarLauncherTheme
import com.ripostelabs.carlauncher.ui.theme.CarTheme

/**
 * The reverse picture as an overlay window, Riposte OS 0.2 only.
 *
 * <pre>
 *   McuOwner ─▶ CarEvents.reverse ─▶ MainActivity collector ─▶ ReverseCameraGate ─▶ render()
 *                                                                                      │
 *   CarPlay (zlink) in front: MainActivity stopped, its composition idle ──────────────┤
 *                                                                                      ▼
 *                                            TYPE_APPLICATION_OVERLAY ─▶ [ReverseCameraScreen]
 * </pre>
 *
 * Why a window and not the activity's content: with a foreign app in front the launcher is
 * stopped, its composition does not run, and a screen inside it can never appear. Reverse then
 * left CarPlay on the panel (2026-09-23). The vendor drew its BackCar view as a system window
 * over whatever was up; so does this. It stacks above [com.ripostelabs.carlauncher.ui.nav.NavBar]
 * because both are overlays and this one is added later. Removing the window disposes the
 * composition, which is what releases the camera.
 */
class ReverseCameraWindow(private val context: Context) {

    private companion object {
        const val TAG = "ReverseCameraWindow"
        const val OVERLAY_APPOP = "SYSTEM_ALERT_WINDOW"
    }

    /** The vendor's two decorations of the feed, read once per picture (see [render]). */
    data class Options(val showRadar: Boolean, val mirrored: Boolean)

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val host = Host()
    private var view: ComposeView? = null
    private var theme by mutableStateOf(BuiltInThemes.DEFAULT)
    private var night by mutableStateOf(false)
    private var verdict by mutableStateOf(ReverseCameraGate.Verdict.HIDDEN)
    private var radar by mutableStateOf<RadarState?>(null)
    private var options by mutableStateOf(Options(showRadar = true, mirrored = false))

    /** Theme for the label and the radar bands; the feed itself has no theme. */
    fun update(theme: CarTheme, night: Boolean) {
        this.theme = theme
        this.night = night
    }

    /**
     * One frame of state. HIDDEN removes the window; anything else adds it once and updates it.
     * [optionsAtShow] runs only as the window is added, as the vendor reads its provider at
     * startBackcar, so a setting flipped mid-reverse waits for the next picture.
     */
    fun render(verdict: ReverseCameraGate.Verdict, radar: RadarState?, optionsAtShow: () -> Options) {
        this.verdict = verdict
        this.radar = radar

        if (verdict == ReverseCameraGate.Verdict.HIDDEN) {
            hide()
            return
        }

        if (view == null) {
            options = optionsAtShow()
        }
        show()
    }

    fun hide() {
        val v = view ?: return
        host.pause()
        runCatching { windowManager.removeViewImmediate(v) }
        view = null
        Log.i(TAG, "reverse window removed")
    }

    private fun show() {
        if (view != null) return
        if (!ensureOverlayAllowed()) return

        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent { Picture() }
        }
        // Full panel, over the bars too; not focusable, so the wheel and fascia keys keep their
        // route, but touches stop here rather than reaching the app underneath.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )

        try {
            windowManager.addView(v, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot add the reverse window", e)
            return
        }
        host.resume()
        view = v
        Log.i(TAG, "reverse window added: $verdict radar=${options.showRadar} mirrored=${options.mirrored}")
    }

    @androidx.compose.runtime.Composable
    private fun Picture() {
        CarLauncherTheme(theme = theme, night = night) {
            ReverseCameraScreen(
                verdict = verdict,
                radar = radar,
                showRadar = options.showRadar,
                mirrored = options.mirrored,
            )
        }
    }

    /** The overlay appop is off by default for a normal install; root turns it on once. */
    private fun ensureOverlayAllowed(): Boolean {
        if (Settings.canDrawOverlays(context)) return true
        val r = RootShell.exec("appops set ${context.packageName} $OVERLAY_APPOP allow")
        if (!r.ok) Log.w(TAG, "no overlay permission and no root to grant it")
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
