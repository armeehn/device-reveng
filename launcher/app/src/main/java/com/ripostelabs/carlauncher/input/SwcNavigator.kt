package com.ripostelabs.carlauncher.input

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import com.ripostelabs.carlauncher.ui.theme.carShape
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
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.WheelFunction
import com.ripostelabs.carlauncher.carlib.WheelKeyMap

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
 *
 * v2.8: both sources now pass through [KeyPump] for repeat / long-press timing, and screens other
 * than Home are driven by [KeyBridge] over Compose's own focus system rather than by this model.
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
 * [com.ripostelabs.carlauncher.ui.AppDrawer]. The grid publishes its live [count], resolved
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
    /**
     * v2.8 — the tile's secondary action (long CENTER), wired by the grid to the same
     * favourite-toggle a touch long-press performs. One gesture, two input paths.
     */
    var longPress: (Int) -> Unit = {}
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

    /**
     * v2.8 — the reachability mirror (LAUNCHER_DESIGN §2.5). When the driver sits on the right,
     * HomeScreen swaps the two side columns, so the ring has to swap with them or a LEFT press
     * walks off toward a column that is no longer there.
     */
    var mirrored = false

    /** Performs the CENTER/ENTER action for the given target. Wired by HomeScreen. */
    var onActivate: (FocusTarget) -> Unit = {}

    /**
     * v2.8 — the long-CENTER secondary action. Returns true when the target had one; a target with
     * no secondary action must say so, or the wheel would confirm a press that did nothing.
     */
    var onSecondary: (FocusTarget) -> Boolean = { false }

    private val _current = mutableStateOf<FocusTarget>(FocusTarget.None)
    /** The currently focused target (Compose-observable). */
    val current: FocusTarget get() = _current.value

    /** v2.8 — where the ring was when the vendor reverse window took the screen. */
    private var interrupted: FocusTarget? = null

    private fun set(t: FocusTarget) {
        _current.value = t
        grid.focusedIndex = (t as? FocusTarget.Grid)?.index
    }

    /** Clear focus (e.g. when leaving Home). The next directional key re-reveals it. */
    fun reset() = set(FocusTarget.None)

    /**
     * v2.8 — park the ring while the vendor reverse window owns the screen, and put it back after.
     *
     * Reverse is an interruption, not a navigation: the driver did not leave Home, the car took
     * the screen away and gave it back. Resuming on the tile they had selected is the difference
     * between the wheel picking up mid-thought and making them hunt for their place again. The
     * pair is idempotent — a spurious END with nothing saved restores nothing.
     */
    fun saveForInterruption() {
        if (current == FocusTarget.None) {
            return
        }
        interrupted = current
    }

    fun restoreAfterInterruption() {
        val saved = interrupted ?: return
        interrupted = null
        set(saved)
    }

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
     * v2.8 — the direction key that points at the glance column (media / climate), and its
     * opposite. Everything below navigates in terms of these two rather than LEFT/RIGHT, so the
     * mirror is applied once, here, instead of at nine call sites.
     */
    private val glanceKey: NavKey get() = if (mirrored) NavKey.RIGHT else NavKey.LEFT
    private val thumbKey: NavKey get() = if (mirrored) NavKey.LEFT else NavKey.RIGHT

    /** The column physically on the [nav] side of the grid, after the mirror. */
    private fun sideAt(nav: NavKey): FocusTarget =
        if (nav == glanceKey) leftTop() else rightTop()

    /**
     * Handle a directional / CENTER key. Returns true only when something actually happened —
     * focus revealed, focus moved, or an item activated. An edge press that moves nothing
     * returns false so the caller can withhold the haptic/beep confirmation: confirming a move
     * that didn't happen tells the driver the ring is somewhere it isn't. The first press on a
     * cleared ring just reveals focus without moving.
     *
     * The ring's wrap decisions are deliberate and unchanged: the thumb column wraps (see
     * [FocusTarget.Quick]); the glance column and the grid's side edges stop. This flag only
     * makes the feedback honest about them.
     */
    fun onKey(nav: NavKey): Boolean {
        if (current == FocusTarget.None) {
            // First press on a cleared ring only REVEALS focus — including CENTER. Activating
            // here would launch the first grid app with no visual indication of what was
            // targeted (a blind launch from the wheel). The next press acts on the revealed item.
            set(firstTarget())
            return true
        }
        return when (nav) {
            NavKey.CENTER -> activate()
            NavKey.UP, NavKey.DOWN, NavKey.LEFT, NavKey.RIGHT -> move(nav)
            else -> false
        }
    }

    /**
     * v2.8 — the long-CENTER secondary action for the focused target. Returns false when the ring
     * is hidden or the target has none, so the caller can stay silent rather than confirm nothing.
     */
    fun onLongPress(): Boolean {
        val c = current
        if (c == FocusTarget.None) {
            return false
        }
        if (c is FocusTarget.Grid && c.index >= grid.count) {
            return false
        }
        return onSecondary(c)
    }

    private fun activate(): Boolean {
        val c = current
        if (c is FocusTarget.Grid && c.index >= grid.count) return false
        onActivate(c)
        return true
    }

    /** True when the key changed the focused target; false against a non-wrapping edge. */
    private fun move(nav: NavKey): Boolean {
        val target = next(current, nav)
        if (target == current) {
            return false
        }
        set(target)
        return true
    }

    private fun next(c: FocusTarget, nav: NavKey): FocusTarget = when (c) {
        // v2.8: the side columns move in terms of glanceKey/thumbKey so the reachability mirror
        // swaps them without a second copy of this table.
        is FocusTarget.Media -> when {
            nav == NavKey.DOWN && showClimate -> FocusTarget.Climate
            nav == thumbKey -> if (showNav) FocusTarget.Nav else centerTarget()
            else -> c
        }

        is FocusTarget.Climate -> when {
            nav == NavKey.UP && showMedia -> FocusTarget.Media
            nav == thumbKey -> if (showNav) FocusTarget.Nav else centerTarget()
            else -> c
        }

        is FocusTarget.Nav -> when {
            nav == NavKey.DOWN -> if (grid.count > 0) FocusTarget.Grid(0) else c
            nav == glanceKey -> leftTop()
            nav == thumbKey -> rightTop()
            else -> c
        }

        is FocusTarget.Grid -> {
            val cols = grid.columns.coerceAtLeast(1)
            val n = grid.count
            val i = c.index.coerceIn(0, (n - 1).coerceAtLeast(0))
            val col = i % cols
            val row = i / cols
            when (nav) {
                // v2.8: tile order never mirrors — only the column we fall out into does.
                NavKey.LEFT -> if (col > 0) FocusTarget.Grid(i - 1) else sideAt(NavKey.LEFT)
                NavKey.RIGHT ->
                    if (col < cols - 1 && i + 1 < n) FocusTarget.Grid(i + 1) else sideAt(NavKey.RIGHT)
                NavKey.UP -> if (row > 0) FocusTarget.Grid(i - cols) else if (showNav) FocusTarget.Nav else c
                // If a row exists below but the cell directly beneath us is past the end (a
                // shorter last row), clamp to the last tile so the final row is still reachable.
                // v2.8: on the last row, wrap to the top of the same column instead of stopping —
                // holding DOWN now cycles the grid rather than jamming against the bottom.
                NavKey.DOWN ->
                    if (row < (n - 1) / cols) FocusTarget.Grid((i + cols).coerceAtMost(n - 1))
                    else FocusTarget.Grid(col)
                else -> c
            }
        }

        // v2.8: the thumb column is a ring — UP off the top lands on the bottom entry and back.
        // It is short (four quick tiles plus the radio card), so wrapping is quicker than
        // reversing, and a driver holding UP never needs to look to know it stopped.
        is FocusTarget.Quick -> when {
            nav == NavKey.UP -> when {
                c.index > 0 -> FocusTarget.Quick(c.index - 1)
                showRadio -> FocusTarget.Radio
                else -> FocusTarget.Quick((quickCount - 1).coerceAtLeast(0))
            }
            nav == NavKey.DOWN -> when {
                c.index + 1 < quickCount -> FocusTarget.Quick(c.index + 1)
                showRadio -> FocusTarget.Radio
                else -> FocusTarget.Quick(0)
            }
            nav == glanceKey -> if (showNav) FocusTarget.Nav else centerTarget()
            else -> c
        }

        is FocusTarget.Radio -> when {
            nav == NavKey.UP && quickCount > 0 -> FocusTarget.Quick(quickCount - 1)
            nav == NavKey.DOWN && quickCount > 0 -> FocusTarget.Quick(0)
            nav == glanceKey -> if (showNav) FocusTarget.Nav else centerTarget()
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

    // v2.8: the old fromSwc(SwcKey) helper is gone. It swallowed key-ups, which was harmless while
    // a press was a single instantaneous event and is not now that [KeyPump] times both edges —
    // a helper that silently drops releases is a trap for the next caller. Decode the key index
    // with [fromCarKey] and route the press state yourself.

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

    /**
     * Learned wheel function → NavKey, the resistive-wheel twin of [fromCarKey]. The vendor
     * learn app lets a button mean any of ~30 functions; only the ones this launcher owns map.
     * MODE is the vendor's source selector, so it lands where CAR_KEY_MEDIA does.
     */
    fun fromWheel(function: WheelFunction): NavKey? = when (function) {
        WheelFunction.PREV -> NavKey.MEDIA_PREV
        WheelFunction.NEXT -> NavKey.MEDIA_NEXT
        WheelFunction.MODE, WheelFunction.MUSIC -> NavKey.OPEN_MEDIA
        WheelFunction.FM -> NavKey.OPEN_RADIO
        WheelFunction.HOME -> NavKey.HOME
        WheelFunction.BACK -> NavKey.BACK
        WheelFunction.OK -> NavKey.CENTER
        else -> null // volume, phone, voice, cameras… — the gateway's business
    }

    /**
     * Resolve one [CarEvents.SwcKey] against the learned map. A `STEER_WHEEL_INFOR` LPARAM
     * is a slot + 1, not a CAR_KEY code: with a learned map, only its functions fire; with
     * none (never learned, or unparseable) the legacy CAR_KEY reading is all there is, so it
     * stays as the fallback rather than going dark. Panel-fallback keys are CAR_KEY codes.
     */
    fun resolve(key: CarEvents.SwcKey, wheel: WheelKeyMap): NavKey? {
        if (key.space == CarEvents.KeySpace.CAR_KEY) {
            return fromCarKey(key.keyIndex)
        }
        if (wheel.isEmpty) {
            return fromCarKey(key.keyIndex)
        }
        val function = wheel.functionOf(WheelKeyMap.slotOfLparam(key.keyIndex)) ?: return null
        return fromWheel(function)
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
