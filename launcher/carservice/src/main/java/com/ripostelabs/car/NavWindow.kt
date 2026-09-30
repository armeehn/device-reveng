package com.ripostelabs.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The nav bar as a system window that reserves its inset (os/CARHAL.md "Nav bar").
 *
 *     launcher NavBarPolicy ── setNavBar(state, colours) ──▶ this window, bottom edge
 *     a touch on it          ── ICarListener.onNavInteract ─▶ launcher: policy.onInteract()
 *
 * The launcher's own overlay (TYPE_APPLICATION_OVERLAY) cannot provide insets, so apps laid out
 * under it and its strip covered their bottom-edge buttons. As system uid this window takes a
 * system type and declares `providedInsets` for navigationBars (hidden API, open to a
 * platform-signed app): DisplayPolicy then shrinks every app that does not hide its own bars.
 * On the bench DAVx5's intro arrow moved from y 575-659 to 515-599 above a 96 px bar.
 *
 * Same look as the launcher's: 60 % surface, Back / Home / Apps, a pill on a 24 dp strip folded.
 */
class NavWindow(private val context: Context, private val onTouch: () -> Unit) : NavPanel {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val keys = Executors.newSingleThreadExecutor { r -> Thread(r, "nav-keys").apply { isDaemon = true } }
    private var root: FrameLayout? = null

    override fun show(state: Int, colors: IntArray) {
        main.post { render(state, colors) }
    }

    private fun render(state: Int, colors: IntArray) {
        val heightDp = NavPanel.heightDp(state)
        if (heightDp == 0) {
            remove()
            return
        }

        val r = root ?: add(heightDp) ?: return
        r.removeAllViews()
        r.addView(if (state == ICarService.NAV_EXPANDED) keysStrip(colors) else handle(colors))
        val params = (r.layoutParams as WindowManager.LayoutParams).apply { height = px(heightDp) }
        runCatching { windowManager.updateViewLayout(r, params) }
    }

    private fun add(heightDp: Int): FrameLayout? {
        val r = FrameLayout(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            px(heightDp),
            TYPE_NAVIGATION_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            title = TITLE
            // The bar sits on the edge it reserves; it must not be pushed up by its own inset.
            fitInsetsTypes = 0
        }
        if (!provideInsets(params)) {
            return null
        }

        try {
            windowManager.addView(r, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot add the nav bar window", e)
            return null
        }
        root = r
        return r
    }

    private fun remove() {
        val r = root ?: return
        runCatching { windowManager.removeViewImmediate(r) }
        root = null
    }

    /**
     * params.providedInsets = [InsetsFrameProvider(owner, 0, navigationBars())], the Android 14
     * form. Hidden, so by reflection; a window without it would be the overlay again, so a
     * failure draws nothing and the launcher keeps its own.
     */
    private fun provideInsets(params: WindowManager.LayoutParams): Boolean = try {
        val provider = Class.forName(INSETS_PROVIDER)
        val ctor = provider.getConstructor(Any::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val one = ctor.newInstance(Binder(), 0, WindowInsets.Type.navigationBars())
        val array = java.lang.reflect.Array.newInstance(provider, 1)
        java.lang.reflect.Array.set(array, 0, one)
        WindowManager.LayoutParams::class.java.getField(PROVIDED_INSETS).set(params, array)
        true
    } catch (e: ReflectiveOperationException) {
        Log.e(TAG, "no providedInsets on this build: nav bar not drawn", e)
        false
    }

    private fun keysStrip(colors: IntArray): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(withAlpha(colors[SURFACE], BAR_ALPHA))
        }
        row.addView(key(R.drawable.nav_back, "Back", colors[ON_SURFACE]) { inject(KEYCODE_BACK) })
        row.addView(key(R.drawable.nav_home, "Home", colors[PRIMARY]) { home() })
        row.addView(key(R.drawable.nav_apps, "Apps", colors[ON_SURFACE]) { inject(KEYCODE_APP_SWITCH) })
        return row
    }

    // Every key press is an interaction too: the launcher keeps the strip up another 3 s.
    private fun key(icon: Int, label: String, tint: Int, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        background = null
        contentDescription = label
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val size = px(KEY_DP)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginStart = px(KEY_GAP_DP) / 2
            marginEnd = px(KEY_GAP_DP) / 2
        }
        setPadding(px(KEY_PAD_DP), px(KEY_PAD_DP), px(KEY_PAD_DP), px(KEY_PAD_DP))
        setOnClickListener {
            onTouch()
            action()
        }
    }

    /** The folded bar: a translucent strip with an accent pill in the middle. A press anywhere on it unfolds it. */
    private fun handle(colors: IntArray): View {
        val frame = FrameLayout(context).apply { setBackgroundColor(withAlpha(colors[SURFACE], BAR_ALPHA)) }

        // Rounded ends read as a handle, not a divider line.
        val pill = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = px(NavPanel.PILL_HEIGHT_DP) / 2f
                setColor(withAlpha(colors[PRIMARY], PILL_ALPHA))
            }
        }
        frame.addView(pill, FrameLayout.LayoutParams(px(NavPanel.PILL_WIDTH_DP), px(NavPanel.PILL_HEIGHT_DP), Gravity.CENTER))
        frame.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                onTouch()
            }
            true
        }
        return frame
    }

    private fun home() {
        val intent = Intent().setComponent(LAUNCHER).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "cannot start the launcher", it) }
    }

    // `input keyevent` as system uid, which holds INJECT_EVENTS: no root shell.
    private fun inject(keyCode: Int) {
        keys.execute {
            try {
                ProcessBuilder("input", "keyevent", keyCode.toString()).start().waitFor()
            } catch (e: IOException) {
                Log.w(TAG, "input keyevent $keyCode failed", e)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * FULL_ALPHA).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun px(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "NavWindow"
        const val TITLE = "RiposteNavBar"

        /** WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, hidden in the SDK; accepted from uid 1000. */
        const val TYPE_NAVIGATION_BAR_PANEL = 2024
        const val INSETS_PROVIDER = "android.view.InsetsFrameProvider"
        const val PROVIDED_INSETS = "providedInsets"

        const val SURFACE = 0
        const val ON_SURFACE = 1
        const val PRIMARY = 2

        /** The launcher's BAR_ALPHA: the app underneath shows through the strip. */
        const val BAR_ALPHA = 0.6f
        /** Near-opaque accent: the pill must stand out over any app colour. */
        const val PILL_ALPHA = 0.9f
        const val FULL_ALPHA = 255

        const val KEY_DP = 56
        const val KEY_PAD_DP = 11
        const val KEY_GAP_DP = 96

        const val KEYCODE_BACK = 4
        const val KEYCODE_APP_SWITCH = 187

        val LAUNCHER = ComponentName("com.ripostelabs.carlauncher", "com.ripostelabs.carlauncher.MainActivity")
    }
}
