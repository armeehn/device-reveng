package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.VendorBtState

/**
 * When to knock down btsuite's in-call floating window (the "vendor call display" that
 * appears over the launcher on every call). Pure; `MainActivity` runs it while resumed
 * through `VendorBtService.hideFloatWnd`.
 *
 * What pops up: `BTService.mFloatWnd`, on this landscape unit a `BTFloatWndLandscape3`
 * (`BTService.initBTFloat`, `BTService.java:437-466`): a `WindowManager` view, type 2003
 * (`BTFloatWndLandscape3.java:166-171`), Answer / Hang up / keypad. Not an Activity, not a
 * component: it cannot be `pm disable`d without losing BTService and the phone with it.
 *
 * What triggers it: the module's HFP state line, whatever placed the call. `ParseFEasycom
 * .parseHFPSTAT` (`:381-385`) ends in `updateSpeakingPage` (`:503`, `:556-559`): state > 3
 * posts message 1005, and `BTService.java:269-278` shows the window for states 4/5/6, then
 * re-polls every second while it is down. `isShowFloatWndAllowed()` (`:328-330`) is a
 * hard-coded `true`: no SysVar or setting turns it off; only CarPlay does
 * (`isCarplayConnectAndResume`, zlink CONNECTED). Choosing MCU_KEY 23 over the control
 * broadcast changes nothing here.
 *
 * What stops it: `IBTService.hideBTFloatWnd()` (`BTService.java:1105-1115`) hides the window
 * AND removes the 1005 poll, so a hide holds until the module's next HFP line.
 *
 * ```
 *  module ─HFPSTAT n─▶ btsuite ─msg 1005─▶ window up ─▶ HSHF / SPEAKING_TIME broadcast
 *                                            ▲                       │
 *                                            └──── binder hide ◀── launcher, after [SETTLE_MS]
 * ```
 *
 * [SETTLE_MS] lets message 1005 run before the hide so the hide is not raced. On answer the
 * HSHF 6 line is not broadcast while the audio is on the car (`ParseFEasycom.java:411`); the
 * speaking-time tick that follows is the trigger instead, so the window can reappear for up
 * to a second there. UNVERIFIED on the car: the flash length, and whether the hide's
 * `resetVolume()` (`:1108`) touches the media volume mid-call.
 */
object CallPopupGuard {

    /** Enough for btsuite's main thread to have handled message 1005 (posted before the broadcast). */
    const val SETTLE_MS = 300L

    /**
     * True when [next] is a call and something the popup keys on moved: the call began, the
     * HFP state stepped (ring -> active re-shows it), or the timer ticked (the only signal of
     * an answered call while the audio is on the car).
     */
    fun wants(prev: VendorBtState, next: VendorBtState): Boolean {
        if (next.inCall != true) {
            return false
        }
        if (prev.inCall != true) {
            return true
        }
        return prev.hshf != next.hshf || prev.speakingSec != next.speakingSec
    }
}
