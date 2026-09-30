package com.ripostelabs.carlauncher.ui.nav

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.input.PowerKeyRouter

/**
 * The black screen (RAV4-156): stock's `BtnBlackScreen` and its POWER key default.
 *
 * <pre>
 *   darken() ── 1F 00 01 ──▶ MCU drops the backlight
 *            └─ black full-screen window ─ touch ─▶ light() ── 1F 01 01 ──▶ backlight back
 * </pre>
 *
 * The window is the touch catcher and the fallback: where the MCU ignores `1F` (the farm) the
 * panel is still black. The unit stays awake behind it, so audio and nav prompts go on. Nothing
 * here touches Android's power state; that is [com.ripostelabs.carlauncher.carlib.AccStandby]'s.
 */
class ScreenOffWindow(
    private val context: Context,
    private val send: (McuOwnerProtocol.Screen) -> Unit,
) : PowerKeyRouter.Panel {

    private companion object {
        const val TAG = "ScreenOff"
        const val OVERLAY_APPOP = "SYSTEM_ALERT_WINDOW"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    @Volatile
    override var dark = false
        private set

    override fun darken() {
        dark = true
        main.post { show() }
    }

    /**
     * Also called on reverse and on ACC on while lit: the `1F 01 01` then re-lights an MCU whose
     * last `1F 00` was followed by a standby that closed the port before the touch's frame.
     */
    override fun light() {
        dark = false
        main.post { remove() }
    }

    private fun show() {
        if (!dark || view != null) {
            return
        }
        if (!ensureOverlayAllowed()) {
            dark = false
            return
        }

        // Any touch lights the panel; the press does not reach the app underneath.
        val v = View(context).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "Screen off. Touch to wake."
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    light()
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
            Log.w(TAG, "cannot add the black screen window", e)
            dark = false
            return
        }
        view = v
        send(McuOwnerProtocol.Screen.OFF)
    }

    private fun remove() {
        send(McuOwnerProtocol.Screen.ON)
        val v = view ?: return
        runCatching { windowManager.removeViewImmediate(v) }
        view = null
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
}
