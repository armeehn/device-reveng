package com.ripostelabs.carlauncher.carlib

/**
 * The nav bar drawn by the car service (ICarService API >= 5), which as a system window reserves
 * the navigationBars inset; the launcher's own overlay cannot. The launcher keeps the policy.
 */
interface CarNav {
    /** Show ICarService.NAV_* in [colors] (surface, onSurface, primary); false when no service took it. */
    fun showNav(state: Int, colors: IntArray): Boolean

    /** [action] runs on a binder thread for every touch on the service's bar. */
    fun onNavTouch(action: () -> Unit)
}
