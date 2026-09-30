package com.ripostelabs.carlauncher.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ripostelabs.carlauncher.carlib.IncomingCallView
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.ui.theme.BuiltInThemes
import com.ripostelabs.carlauncher.ui.theme.CarLauncherTheme
import com.ripostelabs.carlauncher.ui.theme.CarTheme
import com.ripostelabs.carlauncher.ui.theme.carShape

/**
 * RAV4-152 — the incoming-call card over any app, Riposte OS 0.2 only.
 *
 * <pre>
 *   ┌──────────────────────────────────────────────────────────┐  top of the panel,
 *   │  Incoming call                                           │  the app underneath
 *   │  Alex Martin                     [ Reject ]  [ Answer ]  │  keeps every touch
 *   │  +1 250 555 0142                                         │  outside the card
 *   └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * Stock btsuite floats the same card as a type-2003 window (`BTFloatWndLandscape.java:53`).
 * With a foreign app in front the launcher is stopped and its composition idle, so this is a
 * window, as [ReverseCameraWindow] is. [IncomingCallGate][com.ripostelabs.carlauncher.carlib.IncomingCallGate]
 * decides; this only draws. Not focusable and not touch-modal: the wheel keys keep their route.
 */
class IncomingCallWindow(
    private val context: Context,
    private val onAnswer: () -> Unit,
    private val onDecline: () -> Unit,
) {

    private companion object {
        const val TAG = "IncomingCallWindow"
        const val OVERLAY_APPOP = "SYSTEM_ALERT_WINDOW"
        const val CARD_WIDTH_DP = 760
        const val CARD_HEIGHT_DP = 132
        const val TOP_MARGIN_PX = 24
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val host = Host()
    private var view: ComposeView? = null
    private var theme by mutableStateOf(BuiltInThemes.DEFAULT)
    private var night by mutableStateOf(false)
    private var caller by mutableStateOf<String?>(null)
    private var number by mutableStateOf<String?>(null)

    fun update(theme: CarTheme, night: Boolean) {
        this.theme = theme
        this.night = night
    }

    /** One frame: [name] is the phonebook's name for the number, null when it has none. */
    fun render(call: IncomingCallView, name: String?) {
        if (!call.show) {
            hide()
            return
        }
        caller = name
        number = call.number
        show()
    }

    fun hide() {
        val v = view ?: return
        host.pause()
        runCatching { windowManager.removeViewImmediate(v) }
        view = null
        Log.i(TAG, "incoming-call window removed")
    }

    private fun show() {
        if (view != null) {
            return
        }
        if (!ensureOverlayAllowed()) {
            return
        }

        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent { Card() }
        }
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (CARD_WIDTH_DP * density).toInt(),
            (CARD_HEIGHT_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = TOP_MARGIN_PX
        }

        try {
            windowManager.addView(v, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot add the incoming-call window", e)
            return
        }
        host.resume()
        view = v
        Log.i(TAG, "incoming-call window added")
    }

    @Composable
    private fun Card() {
        CarLauncherTheme(theme = theme, night = night) {
            Row(
                modifier = Modifier
                    .clip(carShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Party(modifier = Modifier.weight(1f))
                // The Phone screen's pair: error for Reject, primary for Answer.
                val colours = MaterialTheme.colorScheme
                CallButton("Reject", colours.error, colours.onError, onDecline)
                CallButton("Answer", colours.primary, colours.onPrimary, onAnswer)
            }
        }
    }

    /** "Incoming call", then the name (else the number), then the number under a name. */
    @Composable
    private fun Party(modifier: Modifier) {
        val headline = caller ?: number ?: "Unknown caller"
        val subline = if (caller != null) number else null

        Column(modifier = modifier) {
            Text(
                text = "Incoming call",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subline != null) {
                Text(
                    text = subline,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun CallButton(label: String, color: Color, onColor: Color, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .width(160.dp)
                .height(84.dp)
                .clip(carShape(14.dp))
                .background(color)
                .clickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.titleLarge, color = onColor, maxLines = 1)
        }
    }

    /** The overlay appop is off by default for a normal install; root turns it on once. */
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
