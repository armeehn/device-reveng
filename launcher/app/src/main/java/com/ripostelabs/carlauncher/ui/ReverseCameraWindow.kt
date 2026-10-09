package com.ripostelabs.carlauncher.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.ripostelabs.carlauncher.data.SurroundScreen
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
 *
 * While ACC is on the window stays, invisible and untouchable, with the feed running ([Warmth]).
 * The first reverse after a cold boot spent ~15 s finding the camera signal (AisCameraWorker's
 * warm-up); now that happens at ACC on, and reverse only makes the window visible:
 *
 *     ACC on ──▶ WARM (alpha 0, feed open) ──reverse──▶ FULL ──out of reverse──▶ WARM
 *     ACC off ─▶ NONE (window removed, camera released before standby)
 *
 * The overlays added later (screensaver, call card, hotbar, nav bars) all step aside on reverse.
 */
class ReverseCameraWindow(
    private val context: Context,
    private val onToggleGuideLines: (Boolean) -> Unit = {},
) {

    companion object {
        private const val TAG = "ReverseCameraWindow"
        private const val OVERLAY_APPOP = "SYSTEM_ALERT_WINDOW"
        private const val INVISIBLE = 0f

        /** The window for [verdict]: a picture is always FULL; no picture keeps the feed while WARM. */
        fun plan(verdict: ReverseCameraGate.Verdict, warmth: Warmth): Layout = when {
            verdict != ReverseCameraGate.Verdict.HIDDEN -> Layout.FULL
            warmth == Warmth.WARM -> Layout.WARM
            else -> Layout.NONE
        }
    }

    /** Whether the feed stays open between pictures. */
    enum class Warmth {
        WARM,
        COLD;

        companion object {
            /** The 360 view takes the AIS client while it is shown ([SurroundScreen]). */
            fun of(accOn: Boolean, ownerActive: Boolean, permissionGranted: Boolean, surroundShown: Boolean): Warmth =
                if (accOn && ownerActive && permissionGranted && !surroundShown) WARM else COLD
        }
    }

    /** No window, an invisible one with the feed running, or the picture. */
    enum class Layout { NONE, WARM, FULL }

    /** The vendor's two decorations of the feed and our guide lines, read once per picture (see [render]). */
    data class Options(
        val showRadar: Boolean,
        val mirrored: Boolean,
        val guideLines: Boolean = true,
        /** "Dynamic (steering)" in Reverse camera settings: the lines follow [steer]. */
        val dynamicGuides: Boolean = false,
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val host = Host()
    private var view: ComposeView? = null
    private var theme by mutableStateOf(BuiltInThemes.DEFAULT)
    private var night by mutableStateOf(false)
    private var verdict by mutableStateOf(ReverseCameraGate.Verdict.HIDDEN)
    private var radar by mutableStateOf<RadarState?>(null)
    private var options by mutableStateOf(Options(showRadar = true, mirrored = false))
    private var steeringDeg by mutableStateOf<Double?>(null)

    /** The picture is on screen (FULL); WARM keeps the feed but nobody sees it. */
    private var shown by mutableStateOf(false)
    private var warmth = Warmth.COLD
    private var layout = Layout.NONE
    private var wanted = ReverseCameraGate.Verdict.HIDDEN

    /** The CAN box's latest steering angle (null = no reading); the dynamic lines follow it. */
    fun steer(deg: Double?) {
        steeringDeg = deg
    }

    /** Theme for the label and the radar bands; the feed itself has no theme. */
    fun update(theme: CarTheme, night: Boolean) {
        this.theme = theme
        this.night = night
    }

    /**
     * One frame of state, laid out by [plan]. [optionsAtShow] runs only as the picture appears,
     * as the vendor reads its provider at startBackcar, so a setting flipped mid-reverse waits
     * for the next picture.
     */
    fun render(verdict: ReverseCameraGate.Verdict, radar: RadarState?, optionsAtShow: () -> Options) {
        wanted = verdict
        this.radar = radar
        apply(plan(verdict, warmth), optionsAtShow)
    }

    /** ACC on: keep the feed open between pictures. COLD lets it go unless a picture is up. */
    fun keepWarm(warmth: Warmth) {
        this.warmth = warmth
        apply(plan(wanted, warmth)) { options }
    }

    fun hide() {
        val v = view ?: return
        host.pause()
        runCatching { windowManager.removeViewImmediate(v) }
        view = null
        layout = Layout.NONE
        shown = false
        verdict = ReverseCameraGate.Verdict.HIDDEN
        Log.i(TAG, "reverse window removed")
    }

    private fun apply(next: Layout, optionsAtShow: () -> Options) {
        when (next) {
            Layout.NONE -> hide()
            // The composition keeps PREVIEW, so the camera it holds stays open.
            Layout.WARM -> {
                verdict = ReverseCameraGate.Verdict.PREVIEW
                place(Layout.WARM)
            }
            Layout.FULL -> {
                if (layout != Layout.FULL) {
                    options = optionsAtShow()
                }
                verdict = wanted
                place(Layout.FULL)
            }
        }
    }

    /** Adds the window in [next], or moves an existing one to it without touching the feed. */
    private fun place(next: Layout) {
        shown = next == Layout.FULL
        val v = view
        if (v != null) {
            if (layout != next) {
                v.importantForAccessibility = accessibility(next)
                runCatching { windowManager.updateViewLayout(v, params(next)) }
                    .onFailure { Log.w(TAG, "cannot move the reverse window to $next", it) }
                layout = next
                Log.i(TAG, "reverse window $next: $verdict")
            }
            return
        }

        show(next)
    }

    /**
     * Full panel, over the bars too; not focusable, so the wheel and fascia keys keep their
     * route. FULL stops touches rather than letting them reach the app underneath; WARM is
     * invisible (SurfaceFlinger skips a zero-alpha layer) and lets every touch through.
     */
    private fun params(layout: Layout): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val shown = if (layout == Layout.FULL) {
            flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            shown,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // The whole panel: a bar's inset must never shrink the feed.
            fitInsetsTypes = 0
            if (layout != Layout.FULL) {
                alpha = INVISIBLE
            }
        }
    }

    /** An unseen window is not on screen for accessibility (or uiautomator) either. */
    private fun accessibility(layout: Layout): Int = if (layout == Layout.FULL) {
        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    } else {
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun show(next: Layout) {
        if (!ensureOverlayAllowed()) return

        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent { Picture() }
        }
        v.importantForAccessibility = accessibility(next)
        try {
            windowManager.addView(v, params(next))
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot add the reverse window", e)
            return
        }
        host.resume()
        view = v
        layout = next
        Log.i(TAG, "reverse window added $next: $verdict radar=${options.showRadar} mirrored=${options.mirrored}")
    }

    @androidx.compose.runtime.Composable
    private fun Picture() {
        CarLauncherTheme(theme = theme, night = night) {
            Box(modifier = Modifier.fillMaxSize()) {
                ReverseCameraScreen(
                    verdict = verdict,
                    radar = radar,
                    showRadar = options.showRadar,
                    mirrored = options.mirrored,
                    shown = shown,
                )

                // Guide lines and their chip over the feed. HomeScreen's ReverseOverlay draws
                // them in the activity, and this window sits above it, so on 0.2 the picture
                // hid them (bench, 2026-09-26). No radar here: the screen above draws its own.
                ReverseOverlay(
                    visible = verdict == ReverseCameraGate.Verdict.PREVIEW,
                    guideLines = options.guideLines,
                    onToggleGuideLines = { on ->
                        options = options.copy(guideLines = on)
                        onToggleGuideLines(on)
                    },
                    steeringDeg = if (options.dynamicGuides) steeringDeg else null,
                    mirrored = options.mirrored,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
