package com.ripostelabs.carlauncher.ui.nav

/**
 * RAV4-200 — the pure half of the edge hotbar: which favourites fit, and which swipe opens it.
 *
 * Stock CustomerUI slides a strip of apps in from the left edge on a 2-finger swipe
 * (`AppInfoLeftRightSlidersView`, `GlobalGestureHelp.java:229-231`). Ours lives with the nav bar:
 * its star key or a 2-finger swipe on the bar opens it.
 */
object HotbarPolicy {

    /** How far two fingers travel right before the strip opens. */
    const val SWIPE_OPEN_DP = 48f

    /** Fingers a stock-style hotbar swipe takes. One finger stays a tap on the keys. */
    private const val SWIPE_POINTERS = 2

    /**
     * The packages to show: favourites still installed ([labels] holds only those), minus the
     * launcher itself, in label order, cut at [capacity].
     */
    fun slots(favorites: Collection<String>, labels: Map<String, String>, self: String, capacity: Int): List<String> =
        favorites
            .filter { it != self && labels.containsKey(it) }
            .sortedBy { labels.getValue(it).lowercase() }
            .take(capacity)

    /** Whole slots that fit between the top padding and the nav bar. */
    fun capacity(panelDp: Int, navBarDp: Int, slotDp: Int, paddingDp: Int): Int {
        val room = panelDp - navBarDp - 2 * paddingDp
        if (room <= 0) {
            return 0
        }
        return room / slotDp
    }

    /** True for a rightward swipe of two or more fingers past [SWIPE_OPEN_DP]. */
    fun swipeOpens(pointers: Int, dxDp: Float): Boolean =
        pointers >= SWIPE_POINTERS && dxDp >= SWIPE_OPEN_DP
}
