package com.ripostelabs.carlauncher.ui.nav

import com.ripostelabs.carlauncher.data.NavBarMode

/** What the overlay window shows: nothing, a thin edge handle, or the 64 dp key strip. */
enum class NavBarState { HIDDEN, HANDLE, EXPANDED }

/**
 * Decides what [NavBar] shows while a foreign app is in front. Pure, so the car's two
 * complaints (a strip over wireless CarPlay, a strip that never leaves) are JVM-tested.
 *
 * <pre>
 *   foreground = projection ──────────────▶ HIDDEN  (CarPlay carries its own Home)
 *   foreground = other app  ──▶ EXPANDED ──3 s──▶ HANDLE ──tap/swipe──▶ EXPANDED ──3 s──▶ …
 *   foreground = launcher   ──▶ no decision yet (onPause runs before the next app resumes)
 * </pre>
 *
 * ALWAYS_SHOWN skips the timer; OFF never leaves HIDDEN. Both still yield to projection.
 */
class NavBarPolicy(private val mode: NavBarMode, private val selfPackage: String) {

    companion object {
        /** Riposte OS 0.2 projection suite (CarPlayActivity); see `SourceLabels.PROJECTION`. */
        const val PROJECTION_PACKAGE = "com.ripostelabs.projection"
        const val AUTO_HIDE_MS = 3_000L
        /** Cadence of the foreground read while the bar is up or hidden behind projection. */
        const val FOREGROUND_POLL_MS = 1_500L
        /** Cadence right after onPause, until something other than the launcher is in front. */
        const val SETTLE_POLL_MS = 250L
    }

    var state = NavBarState.HIDDEN
        private set
    private var overProjection = false

    /** The package in front from `dumpsys activity`; null when unknown (no root). */
    fun onForeground(pkg: String?): NavBarState {
        if (pkg == selfPackage) return state

        if (pkg == PROJECTION_PACKAGE) {
            overProjection = true
            state = NavBarState.HIDDEN
            return state
        }

        // First sight of a foreign app, or back from projection: a fresh show. A later poll
        // over the same app leaves a collapsed handle alone.
        if (overProjection || state == NavBarState.HIDDEN) {
            overProjection = false
            state = shown()
        }
        return state
    }

    /** A touch on the handle or a key: expand (again) for another [AUTO_HIDE_MS]. */
    fun onInteract(): NavBarState {
        if (overProjection) return state
        state = shown()
        return state
    }

    fun onTimeout(): NavBarState {
        if (armsTimer()) state = NavBarState.HANDLE
        return state
    }

    /** The launcher is back in front. */
    fun onHide() {
        overProjection = false
        state = NavBarState.HIDDEN
    }

    fun armsTimer(): Boolean = mode == NavBarMode.AUTO_HIDE && state == NavBarState.EXPANDED

    fun nextPollMs(): Long =
        if (state == NavBarState.HIDDEN && !overProjection) SETTLE_POLL_MS else FOREGROUND_POLL_MS

    private fun shown(): NavBarState =
        if (mode == NavBarMode.OFF) NavBarState.HIDDEN else NavBarState.EXPANDED
}
