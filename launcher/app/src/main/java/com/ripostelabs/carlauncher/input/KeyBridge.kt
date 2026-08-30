package com.ripostelabs.carlauncher.input

import android.view.KeyEvent
import android.view.Window

/**
 * v2.8 — the roving focus ring for every screen that is not Home.
 *
 * Home has [LauncherFocus], a hand-written model of its three fixed columns. Nothing else in the
 * launcher has a fixed layout: the settings suite alone is twelve screens of scrolling rows, and
 * MediaScreen / RadioScreen / ThemesScreen each own their own arrangement. Writing a second
 * hand-rolled ring per screen would mean a focus model that drifts out of sync with the layout
 * above it every time a row is added — the failure the settings suite would hit first and notice
 * last.
 *
 * So we drive Compose's own focus system instead. Every `clickable` is already a focus target with
 * real geometry, and Compose's focus search is a proper 2D spatial search over it. The only thing
 * missing is a way in: the vendor SWC keys arrive as a *broadcast* (CAR_API §4), never as a
 * [KeyEvent], so they never reach the composition. This bridge synthesises the KeyEvent Compose
 * would have received.
 *
 * [Window.superDispatchKeyEvent] is deliberate: it hands the event to the view hierarchy *below*
 * `Activity.dispatchKeyEvent`, so a synthetic key cannot re-enter our own dispatcher and loop.
 *
 * Not verified on the head unit — there is no device attached to the machine this was written on.
 * What is verified is that the mechanism is the framework's own, not a guess about vendor
 * behaviour: if Compose can focus a row by touch, this reaches it.
 */
class KeyBridge(private val window: Window) {

    /**
     * Move focus one step in [nav], wrapping at the edge.
     *
     * Wrap-around has no framework API, so it is composed from the one that exists: when a move is
     * refused we are against an edge, and walking as far as possible in the *opposite* direction
     * lands on the far end of the same axis. Bounded by [MAX_WRAP_STEPS] so a focus graph that
     * never refuses a move (a cycle, a mis-built list) cannot spin the main thread.
     */
    fun move(nav: NavKey): Boolean {
        val forward = keyCodeFor(nav) ?: return false
        if (send(forward)) {
            return true
        }

        val back = keyCodeFor(opposite(nav)) ?: return false
        var steps = 0
        while (steps < MAX_WRAP_STEPS && send(back)) {
            steps++
        }
        return steps > 0
    }

    /**
     * Activate the focused item. `Modifier.clickable` already answers DPAD-CENTER when focused, so
     * the synthetic key is the whole implementation — no registry of per-screen callbacks to keep
     * current.
     */
    fun activate(): Boolean = send(KeyEvent.KEYCODE_DPAD_CENTER)

    /** Send a full down/up pair; `clickable` fires on the up, focus search on the down. */
    private fun send(keyCode: Int): Boolean {
        val down = window.superDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        val up = window.superDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return down || up
    }

    private fun keyCodeFor(nav: NavKey): Int? = when (nav) {
        NavKey.UP -> KeyEvent.KEYCODE_DPAD_UP
        NavKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        NavKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        NavKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        NavKey.CENTER -> KeyEvent.KEYCODE_DPAD_CENTER
        else -> null
    }

    private fun opposite(nav: NavKey): NavKey = when (nav) {
        NavKey.UP -> NavKey.DOWN
        NavKey.DOWN -> NavKey.UP
        NavKey.LEFT -> NavKey.RIGHT
        NavKey.RIGHT -> NavKey.LEFT
        else -> nav
    }

    private companion object {
        /** A screen with more focusable rows than this does not wrap; it stops at the edge. */
        const val MAX_WRAP_STEPS = 64
    }
}
