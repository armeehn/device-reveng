package com.ripostelabs.carlauncher.ui.nav

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ripostelabs.carlauncher.MainActivity
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.ui.theme.ThemeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The launcher's own navigation bar, drawn as an overlay window while another app is in front.
 *
 * "Replace the system bars" runs `cmd statusbar disable-for-setup true`, and on the Riposte OS
 * 0.2 base that takes SystemUI's navigation bar (the gesture pill) with it: no way back from a
 * foreign app on a unit whose fascia HOME key cannot leave one on the owner path (RAV4-101).
 * Rather than let SystemUI back in, the launcher draws the three keys itself, in its own theme.
 *
 * <pre>
 *   foreign app in front ─── MainActivity.onPause ──▶ show()   ┌───────────────────────────┐
 *   launcher in front    ─── MainActivity.onResume ─▶ hide()   │  ◀ Back    ⌂ Home   ▦ Apps │
 *                                                              └───────────────────────────┘
 * </pre>
 *
 * Back and Apps are key injections through root (`input keyevent`), the path the wheel keys
 * already use; Home is a plain launch of [MainActivity]. The window needs the "draw over other
 * apps" appop, which the launcher grants itself through root the first time it is missing.
 */
class NavBar(private val context: Context) {

    private companion object {
        const val TAG = "NavBar"
        const val HEIGHT_DP = 64
        const val ICON_DP = 34
        const val KEYCODE_BACK = 4
        const val KEYCODE_APP_SWITCH = 187
        const val OVERLAY_APPOP = "SYSTEM_ALERT_WINDOW"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val host = Host()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var view: ComposeView? = null
    private var colors by mutableStateOf<ThemeColors?>(null)
    private var enabled = false

    /** Theme and whether the system bars are suppressed; only then is there a bar to replace. */
    fun update(colors: ThemeColors, enabled: Boolean) {
        this.colors = colors
        this.enabled = enabled
        if (!enabled) hide()
    }

    fun show() {
        if (!enabled || view != null) return
        if (!ensureOverlayAllowed()) return

        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent { Bar() }
        }
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (HEIGHT_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }

        try {
            windowManager.addView(v, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot add the nav bar window", e)
            return
        }
        host.resume()
        view = v
    }

    fun hide() {
        val v = view ?: return
        host.pause()
        runCatching { windowManager.removeViewImmediate(v) }
        view = null
    }

    /** The overlay appop is off by default for a normal install; root turns it on once. */
    private fun ensureOverlayAllowed(): Boolean {
        if (Settings.canDrawOverlays(context)) return true
        val r = RootShell.exec("appops set ${context.packageName} $OVERLAY_APPOP allow")
        if (!r.ok) Log.w(TAG, "no overlay permission and no root to grant it")
        return Settings.canDrawOverlays(context)
    }

    private fun back() = inject(KEYCODE_BACK)

    private fun recents() = inject(KEYCODE_APP_SWITCH)

    private fun home() {
        val intent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        context.startActivity(intent)
    }

    private fun inject(keyCode: Int) {
        io.launch { RootShell.exec("input keyevent $keyCode") }
    }

    @Composable
    private fun Bar() {
        val c = colors ?: return
        Box(
            modifier = Modifier.fillMaxSize().background(Color(c.surface)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(96.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Key(Icons.AutoMirrored.Filled.ArrowBack, "Back", Color(c.onSurface), ::back)
                Key(Icons.Filled.Home, "Home", Color(c.primary), ::home)
                Key(Icons.Filled.Apps, "Apps", Color(c.onSurface), ::recents)
            }
        }
    }

    @Composable
    private fun Key(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
        IconButton(onClick = onClick, modifier = Modifier.size((HEIGHT_DP - 8).dp)) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(ICON_DP.dp))
        }
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
