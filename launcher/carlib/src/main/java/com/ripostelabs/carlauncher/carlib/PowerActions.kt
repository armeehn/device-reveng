package com.ripostelabs.carlauncher.carlib

import android.util.Log

/**
 * The head unit's power actions on whichever path is live.
 *
 *     OWNER  (Riposte OS 0.2, McuOwner holds the MCU) ─▶ root shell: svc power reboot
 *     VENDOR (stock, eventcenter bound)               ─▶ IEventService.sendSoftWareReboot()
 *
 * 0.2 removes eventcenter, so the vendor call alone reached nothing and Reboot in System & about
 * did nothing (bench, 2026-09-27). Root for the launcher is part of 0.2 (riposte-root.sh).
 */
object PowerActions {

    enum class Path { OWNER, VENDOR }

    /** A clean Android reboot: PowerManager's own path, not a kernel reset. */
    const val ROOT_REBOOT = "svc power reboot"

    private const val TAG = "PowerActions"

    /** True when the reboot was handed off; false when the root shell refused it. */
    fun reboot(path: Path, vendor: () -> Unit, root: (String) -> RootShell.Result?): Boolean {
        if (path == Path.VENDOR) {
            vendor()
            return true
        }

        val result = root(ROOT_REBOOT)
        Log.i(TAG, "$ROOT_REBOOT -> ${result?.code}")
        return result?.ok == true
    }
}
