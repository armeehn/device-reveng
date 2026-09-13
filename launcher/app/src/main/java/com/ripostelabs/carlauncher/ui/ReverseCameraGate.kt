package com.ripostelabs.carlauncher.ui

/**
 * ReverseCameraGate — decides whether the launcher draws the reverse camera itself.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     CarEvents.reverse ──┐
 *     mcuOwner != null ───┼─▶ decide() ──▶ Verdict ──▶ ReverseCameraScreen (MainActivity overlay)
 *     CAMERA granted ─────┘
 *
 * On a stock or 0.1 slot the vendor's AUXCamera composites its own reverse window over us, so
 * we must draw nothing (see [ReverseOverlay]'s coexistence rule). On a Riposte OS 0.2 slot that
 * app is gone and the MCU owner is ours, so the launcher has to show the feed. The third input
 * only picks what the screen says: a missing grant is a message, never a hidden screen, because
 * a driver in reverse must see *why* there is no picture.
 *
 * Pure on purpose — the table test covers every row.
 */
object ReverseCameraGate {

    enum class Verdict {
        /** Not in reverse, or the vendor camera app owns the screen. Draw nothing. */
        HIDDEN,

        /** In reverse, we own the car link, and the camera may be opened. */
        PREVIEW,

        /** In reverse and ours, but CAMERA was never granted. Say so, do not crash. */
        NO_PERMISSION,
    }

    fun decide(reverse: Boolean, ownerActive: Boolean, permissionGranted: Boolean): Verdict {
        if (!reverse || !ownerActive) {
            return Verdict.HIDDEN
        }

        if (!permissionGranted) {
            return Verdict.NO_PERMISSION
        }

        return Verdict.PREVIEW
    }
}
