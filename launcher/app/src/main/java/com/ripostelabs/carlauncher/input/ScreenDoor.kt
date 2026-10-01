package com.ripostelabs.carlauncher.input

/**
 * RAV4-273: opens a launcher screen for a key from any source (panel, wheel, CAN box, nav bar).
 *
 * Switching the screen state alone only changes what the launcher draws behind the app in
 * front. Box HOME over Maps did nothing for that reason. When the launcher is not the resumed
 * activity, [toFront] raises its task as well, so the key works from any app.
 */
class ScreenDoor<S>(
    private val inFront: () -> Boolean,
    private val show: (S) -> Unit,
    private val toFront: () -> Unit,
) {

    /** Show [screen]; raise the launcher first when another app covers it. */
    fun open(screen: S) {
        show(screen)
        if (inFront()) {
            return
        }

        toFront()
    }
}
