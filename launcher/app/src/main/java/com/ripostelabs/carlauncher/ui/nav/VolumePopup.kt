package com.ripostelabs.carlauncher.ui.nav

import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.carlib.VolumeReading
import com.ripostelabs.carlauncher.ui.theme.ThemeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The volume popup over any app (RAV4-155): a bar at the top centre for [SHOW_MS] after each
 * report [VolumePopupPolicy] lets through. Stock draws `WindowVolumeLandView` from its gateway.
 *
 * <pre>
 *            ┌──────────────────────────────────────┐
 *            │ 🔊  ███████████████░░░░░░░░░░░   21  │
 *            └──────────────────────────────────────┘
 * </pre>
 *
 * A TYPE_APPLICATION_OVERLAY window, as the launcher's own nav bar uses, so it shows over Maps
 * or a media app. It never takes touch or focus: a tap lands on the app underneath.
 */
class VolumePopup(private val context: Context) {

    private companion object {
        const val TAG = "VolumePopup"
        /** Stock keeps its window 5 s (EventService.java:3117); 3 s is the matrix's call for a driver. */
        const val SHOW_MS = 3_000L
        const val WIDTH_DP = 520
        const val HEIGHT_DP = 72
        const val TOP_DP = 24
        const val ICON_DP = 36
        const val TRACK_DP = 10
        const val SURFACE_ALPHA = 0.92f
        const val TRACK_ALPHA = 0.25f
        const val OVERLAY_APPOP = "SYSTEM_ALERT_WINDOW"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val host = Host()
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: ComposeView? = null
    private var colors by mutableStateOf<ThemeColors?>(null)
    private var reading by mutableStateOf<VolumeReading?>(null)
    private var timer: Job? = null

    fun update(colors: ThemeColors) {
        this.colors = colors
    }

    /** Show [level] now and restart the hide timer, so a held key keeps it up. */
    fun show(level: VolumeReading) {
        reading = level
        if (view == null && addWindow() == null) {
            return
        }

        timer?.cancel()
        timer = ui.launch {
            delay(SHOW_MS)
            hide()
        }
    }

    fun hide() {
        timer?.cancel()
        timer = null
        val v = view ?: return
        host.pause()
        runCatching { windowManager.removeViewImmediate(v) }
        view = null
    }

    private fun addWindow(): ComposeView? {
        if (!ensureOverlayAllowed()) {
            return null
        }

        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent { Popup() }
        }
        val params = WindowManager.LayoutParams(
            px(WIDTH_DP),
            px(HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = px(TOP_DP)
            title = TAG
        }

        try {
            windowManager.addView(v, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot add the volume window", e)
            return null
        }
        host.resume()
        view = v
        return v
    }

    private fun px(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

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

    @Composable
    private fun Popup() {
        val c = colors ?: return
        val r = reading ?: return
        val accent = if (r.muted) Color(c.onSurfaceMuted) else Color(c.primary)
        val fraction = r.level.coerceIn(0, CarService.MAX_VOLUME).toFloat() / CarService.MAX_VOLUME

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(c.surface).copy(alpha = SURFACE_ALPHA), RoundedCornerShape(HEIGHT_DP.dp / 2))
                .padding(horizontal = 24.dp)
                .semantics { contentDescription = if (r.muted) "Volume muted" else "Volume ${r.level}" },
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = if (r.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(ICON_DP.dp))

            // The track, and the level filled in the accent colour.
            Box(
                Modifier
                    .weight(1f)
                    .height(TRACK_DP.dp)
                    .background(Color(c.onSurface).copy(alpha = TRACK_ALPHA), RoundedCornerShape(TRACK_DP.dp / 2)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(accent, RoundedCornerShape(TRACK_DP.dp / 2)),
                )
            }

            Text(
                text = r.level.toString(),
                color = Color(c.onSurface),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(44.dp),
            )
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
