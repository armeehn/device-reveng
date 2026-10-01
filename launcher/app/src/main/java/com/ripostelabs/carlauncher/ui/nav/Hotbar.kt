package com.ripostelabs.carlauncher.ui.nav

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ripostelabs.carlauncher.AppInfo
import com.ripostelabs.carlauncher.ui.theme.ThemeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * RAV4-200 — the edge app hotbar: favourites in a strip that slides in from the left edge.
 *
 * <pre>
 *   ┌────┐
 *   │ ★  │  favourites, label order          volume popup and call card: top centre
 *   │ ▣  │  (HotbarPolicy.slots)             reverse: closes the strip
 *   │ ▣  │
 *   └────┘
 *   ◀ ⌂ ▦ ★ ──── nav bar (64 dp), never covered ────
 * </pre>
 *
 * Opened from [NavBar] (its star key, or a 2-finger swipe on the bar), so it exists only over
 * foreign apps. It is its own overlay window, [STRIP_DP] wide, which leaves the top-centre
 * volume popup and call card clear and stops above the nav bar. A tap outside, a launch, the
 * launcher coming back or [IDLE_MS] without a touch closes it.
 *
 * Favourites, labels and launching come in as functions, so this window knows nothing of
 * the stores behind them.
 */
class Hotbar(
    private val context: Context,
    private val favorites: () -> Set<String>,
    private val resolve: (String) -> AppInfo?,
    private val launch: (AppInfo) -> Unit,
) {

    private companion object {
        const val TAG = "Hotbar"
        const val STRIP_DP = 104
        const val SLOT_DP = 80
        const val ICON_DP = 48
        const val PADDING_DP = 12
        /** The nav bar's own height; the strip stops above it. */
        const val NAV_BAR_DP = 64
        const val IDLE_MS = 8_000L
        const val STRIP_ALPHA = 0.92f
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val host = Host()
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: ComposeView? = null
    private var idle: Job? = null
    private var colors: ThemeColors? = null

    val isOpen: Boolean get() = view != null

    fun update(colors: ThemeColors) {
        this.colors = colors
    }

    /** Slide the strip in, or close it when it is already open. */
    fun toggle() {
        if (isOpen) {
            close()
            return
        }
        open()
    }

    fun open() {
        val c = colors ?: return
        if (isOpen) {
            return
        }

        val apps = loadApps()
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent { Strip(c, apps) }
            closeOnOutsideTouch(this)
        }
        try {
            windowManager.addView(v, params())
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot add the hotbar window", e)
            return
        }
        host.resume()
        view = v
        armIdle()
    }

    fun close() {
        idle?.cancel()
        val v = view ?: return
        host.pause()
        runCatching { windowManager.removeViewImmediate(v) }
        view = null
    }

    /** Installed favourites that fit, resolved once per open. */
    private fun loadApps(): List<AppInfo> {
        val resolved = favorites().mapNotNull(resolve).associateBy { it.packageName }
        val labels = resolved.mapValues { it.value.label }
        val fit = HotbarPolicy.capacity(panelDp(), NAV_BAR_DP, SLOT_DP, PADDING_DP)
        return HotbarPolicy.slots(resolved.keys, labels, context.packageName, fit).map(resolved::getValue)
    }

    private fun params(): WindowManager.LayoutParams {
        // As tall as its slots, centred in the space above the nav bar.
        return WindowManager.LayoutParams(
            px(STRIP_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            y = -px(NAV_BAR_DP) / 2
        }
    }

    // A touch on the app underneath closes the strip and still reaches the app.
    @SuppressLint("ClickableViewAccessibility")
    private fun closeOnOutsideTouch(v: ComposeView) {
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                close()
            }
            false
        }
    }

    private fun armIdle() {
        idle?.cancel()
        idle = ui.launch {
            delay(IDLE_MS)
            close()
        }
    }

    private fun pick(app: AppInfo) {
        close()
        launch(app)
    }

    private fun panelDp(): Int {
        val m = context.resources.displayMetrics
        return (m.heightPixels / m.density).toInt()
    }

    private fun px(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    @Composable
    private fun Strip(c: ThemeColors, apps: List<AppInfo>) {
        // Starts hidden and targets shown, so the first frame slides in from the edge.
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(visibleState = shown, enter = slideInHorizontally { -it }) {
            Column(
                modifier = Modifier
                    .width(STRIP_DP.dp)
                    .background(
                        Color(c.surface).copy(alpha = STRIP_ALPHA),
                        RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                    )
                    .padding(vertical = PADDING_DP.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (apps.isEmpty()) {
                    Empty(c)
                }
                apps.forEach { Slot(c, it) }
            }
        }
    }

    @Composable
    private fun Slot(c: ThemeColors, app: AppInfo) {
        val icon = remember(app.packageName) { app.icon.toBitmap().asImageBitmap() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(SLOT_DP.dp)
                .clickable { pick(app) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(bitmap = icon, contentDescription = app.label, modifier = Modifier.size(ICON_DP.dp))
            Text(
                text = app.label,
                color = Color(c.onSurface),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }

    @Composable
    private fun Empty(c: ThemeColors) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = Color(c.primary), modifier = Modifier.size(32.dp))
        Text(
            text = "Star apps in the drawer to pin them here",
            color = Color(c.onSurface),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
        )
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
