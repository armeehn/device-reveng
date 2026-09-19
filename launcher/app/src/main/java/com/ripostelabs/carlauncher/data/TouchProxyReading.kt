package com.ripostelabs.carlauncher.data

/**
 * Riposte OS 0.2: the Setup Doctor's "Touch" row, as plain text.
 *
 * The Goodix chip reports X scaled 0..720 and Y 0..1920 under a driver that advertises the
 * reverse; riposte-touchswap (os/touchswap) grabs the driver's device and re-emits it as
 * "Riposte Touch" with the real ranges. When that proxy is missing every touch arrives in the
 * left third of the panel, which is hard to diagnose from the panel itself. On the vendor slot
 * the stock framework copes and the row only says so.
 *
 * Pure: the screen lists the input device names, this decides.
 */
object TouchProxy {

    const val PROXY_DEVICE = "Riposte Touch"

    const val VENDOR_TITLE = "Vendor touch"
    const val VENDOR_DETAIL = "The stock framework maps the panel"
    const val UP_TITLE = "Touch proxy up"
    const val UP_DETAIL = "$PROXY_DEVICE carries the panel with the real ranges"
    const val DOWN_TITLE = "Touch proxy missing"
    const val DOWN_DETAIL = "riposte-touchswap is not running: taps arrive in the left third"

    fun read(ownerActive: Boolean, deviceNames: List<String>): CarLinkReading {
        if (!ownerActive) {
            return CarLinkReading(ok = true, title = VENDOR_TITLE, detail = VENDOR_DETAIL)
        }
        if (PROXY_DEVICE in deviceNames) {
            return CarLinkReading(ok = true, title = UP_TITLE, detail = UP_DETAIL)
        }
        return CarLinkReading(ok = false, title = DOWN_TITLE, detail = DOWN_DETAIL)
    }
}
