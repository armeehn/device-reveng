package com.reveng.carlauncher.input

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.reveng.carlauncher.carlib.CarEvents

/**
 * v0.8 — Steering-Wheel-Control (SWC) key navigation.
 *
 * The whole launcher is drivable without touching the screen: a roving focus ring moves
 * between the Home regions (media / climate on the left, the app grid in the centre, and
 * quick-launch / radio on the right) and within the app grid; CENTER/ENTER activates the
 * focused item; BACK returns Home from a sub-screen. Media transport keys (next / prev /
 * play-pause) are routed straight to the active MediaController even without focus.
 *
 * Two input sources feed one model:
 *   (a) [CarEvents.swcKeys] — the vendor STEER_WHEEL_INFOR broadcast, decoded to CAR_KEY_*.
 *   (b) [MainActivity.dispatchKeyEvent] — real Android [KeyEvent]s (DPAD, ENTER, MEDIA_*),
 *       plus the vendor CAR_KEY_* codes if they surface as KeyEvents.
 * Both map to a single [NavKey] via [SwcNavigator]; [LauncherFocus] owns the focus state.
 */
enum class NavKey {
    UP, DOWN, LEFT, RIGHT,
    CENTER,       // activate the focused item
    BACK,         // leave a sub-screen / return Home
    HOME,         // jump to Home
    MEDIA_NEXT, MEDIA_PREV, MEDIA_PLAY_PAUSE,
    // v2.6: the wheel's source keys open the full Media / Radio screens (§3.3, §3.4).
    OPEN_MEDIA, OPEN_RADIO,
}

/** A focusable region of the Home screen (the app grid is addressed by tile index). */
sealed interface FocusTarget {
    data object None : FocusTarget
    data object Media : FocusTarget
    data object Climate : FocusTarget
    data object Nav : FocusTarget
    data class Grid(val index: Int) : FocusTarget
    data class Quick(val index: Int) : FocusTarget
    data object Radio : FocusTarget
}

/**
 * Shared handle for the centre app grid, whose displayed order / count lives inside
 * [com.reveng.carlauncher.ui.AppDrawer]. The grid publishes its live [count], resolved
 * [columns] and a [launch] by-display-index here; [LauncherFocus] reads them to navigate,
 * and the grid reads [focusedIndex] to draw the highlight. Avoids duplicating the drawer's
 * ordering/filtering logic in the caller.
 */
class GridFocus {
    var focusedIndex by mutableStateOf<Int?>(null)
    var count by mutableIntStateOf(0)
    var columns by mutableIntStateOf(3)
    /** Launch (or open) the display tile at [index]; wired by the grid. */
    var launch: (Int) -> Unit = {}
}

/**
 * The roving-focus state model. Created once by the Activity (so both the key dispatcher and
 * Compose can reach it) and provided to the tree via [LocalLauncherFocus].
 *
 * Layout inputs ([showMedia], [showClimate], [showRadio], [quickCount], [grid]) are kept in
 * sync by HomeScreen; [onActivate] is wired by HomeScreen to perform the CENTER action for the
 * focused target.
 */
class LauncherFocus {
    val grid = GridFocus()

    var showMedia = true
    var showClimate = true
    var showRadio = true
    var showNav = true
    var quickCount = 0

    /** Performs the CENTER/ENTER action for the given target. Wired by HomeScreen. */
    var onActivate: (FocusTarget) -> Unit = {}

    private val _current = mutableStateOf<FocusTarget>(FocusTarget.None)
    /** The currently focused target (Compose-observable). */
    val current: FocusTarget get() = _current.value

    private fun set(t: FocusTarget) {
        _current.value = t
        grid.focusedIndex = (t as? FocusTarget.Grid)?.index
    }

    /** Clear focus (e.g. when leaving Home). The next directional key re-reveals it. */
    fun reset() = set(FocusTarget.None)

    private fun firstTarget(): FocusTarget = centerTarget()

    /** Best central focus target, skipping the Nav card when the home-widget toggle hides it. */
    private fun centerTarget(): FocusTarget = when {
        grid.count > 0 -> FocusTarget.Grid(0)
        showNav -> FocusTarget.Nav
        showMedia -> FocusTarget.Media
        showClimate -> FocusTarget.Climate
        quickCount > 0 -> FocusTarget.Quick(0)
        showRadio -> FocusTarget.Radio
        else -> FocusTarget.Nav // everything hidden: the home screen is empty, so target is moot
    }

    private fun leftTop(): FocusTarget = when {
        showMedia -> FocusTarget.Media
        showClimate -> FocusTarget.Climate
        else -> FocusTarget.Nav
    }

    private fun rightTop(): FocusTarget = when {
        quickCount > 0 -> FocusTarget.Quick(0)
        showRadio -> FocusTarget.Radio
        else -> FocusTarget.Nav
    }

    /**
     * Handle a directional / CENTER key. Returns true (always handled while on Home). The
     * first press on a cleared ring just reveals focus without moving.
     */
    fun onKey(nav: NavKey): Boolean {
        if (current == FocusTarget.None) {
            // First press on a cleared ring only REVEALS focus — including CENTER. Activating
            // here would launch the first grid app with no visual indication of what was
            // targeted (a blind launch from the wheel). The next press acts on the revealed item.
            set(firstTarget())
            return true
        }
        when (nav) {
            NavKey.CENTER -> activate()
            NavKey.UP, NavKey.DOWN, NavKey.LEFT, NavKey.RIGHT -> move(nav)
            else -> {}
        }
        return true
    }

    private fun activate() {
        val c = current
        if (c is FocusTarget.Grid && c.index >= grid.count) return
        onActivate(c)
    }

    private fun move(nav: NavKey) {
        set(next(current, nav))
    }

    private fun next(c: FocusTarget, nav: NavKey): FocusTarget = when (c) {
        is FocusTarget.Media -> when (nav) {
            NavKey.DOWN -> if (showClimate) FocusTarget.Climate else c
            NavKey.RIGHT -> if (showNav) FocusTarget.Nav else centerTarget()
            else -> c
        }

        is FocusTarget.Climate -> when (nav) {
            NavKey.UP -> if (showMedia) FocusTarget.Media else c
            NavKey.RIGHT -> if (showNav) FocusTarget.Nav else centerTarget()
            else -> c
        }

        is FocusTarget.Nav -> when (nav) {
            NavKey.DOWN -> if (grid.count > 0) FocusTarget.Grid(0) else c
            NavKey.LEFT -> leftTop()
            NavKey.RIGHT -> rightTop()
            else -> c
        }

        is FocusTarget.Grid -> {
            val cols = grid.columns.coerceAtLeast(1)
            val n = grid.count
            val i = c.index.coerceIn(0, (n - 1).coerceAtLeast(0))
            val col = i % cols
            val row = i / cols
            when (nav) {
                NavKey.LEFT -> if (col > 0) FocusTarget.Grid(i - 1) else leftTop()
                NavKey.RIGHT -> if (col < cols - 1 && i + 1 < n) FocusTarget.Grid(i + 1) else rightTop()
                NavKey.UP -> if (row > 0) FocusTarget.Grid(i - cols) else if (showNav) FocusTarget.Nav else c
                // If a row exists below but the cell directly beneath us is past the end (a
                // shorter last row), clamp to the last tile so the final row is still reachable.
                NavKey.DOWN -> if (row < (n - 1) / cols) FocusTarget.Grid((i + cols).coerceAtMost(n - 1)) else c
                else -> c
            }
        }

        is FocusTarget.Quick -> when (nav) {
            NavKey.UP -> if (c.index > 0) FocusTarget.Quick(c.index - 1) else c
            NavKey.DOWN -> when {
                c.index + 1 < quickCount -> FocusTarget.Quick(c.index + 1)
                showRadio -> FocusTarget.Radio
                else -> c
            }
            NavKey.LEFT -> if (showNav) FocusTarget.Nav else centerTarget()
            else -> c
        }

        is FocusTarget.Radio -> when (nav) {
            NavKey.UP -> if (quickCount > 0) FocusTarget.Quick(quickCount - 1) else c
            NavKey.LEFT -> if (showNav) FocusTarget.Nav else centerTarget()
            else -> c
        }

        FocusTarget.None -> firstTarget()
    }
}

/** Provided by MainActivity so HomeScreen + the app grid can consult the focus ring. */
val LocalLauncherFocus = staticCompositionLocalOf { LauncherFocus() }

/**
 * Maps both input sources onto [NavKey].
 *
 * SWC broadcast (CAR_KEY_*, CAR_API §4) and real Android [KeyEvent] keycodes are handled;
 * the tuner/left-right rocker doubles as a directional pad so a bare prev/next/tune SWC can
 * still drive the whole ring. The on-device verification table is in the final report.
 */
object SwcNavigator {

    /** Vendor SWC key (from [CarEvents.swcKeys]); only key-down presses produce a NavKey. */
    fun fromSwc(key: CarEvents.SwcKey): NavKey? {
        if (!key.down) return null
        return fromCarKey(key.keyIndex)
    }

    /**
     * CAR_KEY_* index → NavKey.
     *
     * v2.6: MEDIA and RADIO are *source* keys — the wheel already has dedicated PREV/NEXT, so a
     * separate MEDIA key is the vendor's mode selector, not a transport button. They now open
     * the full screens (§3.3, §3.4). Pressing MEDIA again while the media screen is already open
     * toggles play/pause, so the transport it used to provide is still one press away.
     */
    fun fromCarKey(carKey: Int): NavKey? = when (carKey) {
        CarEvents.CAR_KEY_PREV -> NavKey.MEDIA_PREV
        CarEvents.CAR_KEY_NEXT -> NavKey.MEDIA_NEXT
        CarEvents.CAR_KEY_MEDIA -> NavKey.OPEN_MEDIA
        CarEvents.CAR_KEY_RADIO -> NavKey.OPEN_RADIO
        CarEvents.CAR_KEY_HOME -> NavKey.HOME
        CarEvents.CAR_KEY_BACK -> NavKey.BACK
        CarEvents.CAR_KEY_MENU, CarEvents.CAR_KEY_FAV -> NavKey.CENTER
        CarEvents.CAR_KEY_L_TUNE_L -> NavKey.LEFT
        CarEvents.CAR_KEY_L_TUNE_R -> NavKey.RIGHT
        CarEvents.CAR_KEY_R_TUNE_L -> NavKey.UP
        CarEvents.CAR_KEY_R_TUNE_R -> NavKey.DOWN
        else -> null // POWER / PHONE — left to their own handlers
    }

    /** Real Android [KeyEvent] keycode → NavKey. */
    fun fromKeyEvent(keyCode: Int): NavKey? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> NavKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> NavKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> NavKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> NavKey.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
            NavKey.CENTER
        KeyEvent.KEYCODE_BACK -> NavKey.BACK
        KeyEvent.KEYCODE_HOME -> NavKey.HOME
        KeyEvent.KEYCODE_MEDIA_NEXT -> NavKey.MEDIA_NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> NavKey.MEDIA_PREV
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> NavKey.MEDIA_PLAY_PAUSE
        else -> null
    }
}

/**
 * Focus-ring highlight for a region call-site: a MaterialTheme primary ring + gentle scale
 * when [target] is focused, plus a real [focusable] node (best-effort [FocusRequester] sync)
 * so the region participates in Compose focus for accessibility. Colours come from
 * [MaterialTheme.colorScheme].
 */
@Composable
fun Modifier.launcherFocusTarget(
    focus: LauncherFocus,
    target: FocusTarget,
    cornerRadiusDp: Int = 18,
): Modifier {
    val focused = focus.current == target
    val requester = remember { FocusRequester() }
    LaunchedEffect(focused) {
        if (focused) runCatching { requester.requestFocus() }
    }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "focusScale")
    val ring = MaterialTheme.colorScheme.primary
    val shape = carShape(cornerRadiusDp.dp)
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
        .focusRequester(requester)
        .focusable()
}
