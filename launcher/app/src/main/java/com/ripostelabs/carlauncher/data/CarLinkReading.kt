package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.McuOwner

/**
 * Riposte OS 0.2 — the Setup Doctor's "Car link" row, as plain text.
 *
 * On a 0.2 slot the launcher owns the MCU port through [McuOwner]; on a stock or 0.1 slot the
 * vendor binder does. Whichever it is, the first session at the car needs to read the outcome on
 * the panel, not over logcat. This object turns [McuOwner.Status] (or its absence) into one
 * verdict line, using the same readings as the OS acceptance checklist: no frames means the port
 * or baud is wrong; frames without an ACK means the MCU talks but the ACK formula is wrong.
 *
 * Pure: no Android, no flows. The screen collects, this decides.
 */
data class CarLinkReading(
    val ok: Boolean,
    val title: String,
    val detail: String,
)

object CarLink {

    const val VENDOR_TITLE = "Vendor gateway"
    const val VENDOR_CONNECTED = "CarService connected"
    const val VENDOR_DISCONNECTED = "CarService not connected"

    const val IDLE_TITLE = "Owner idle"
    const val IDLE_DETAIL = "Session not started"
    const val BLOCKED_TITLE = "Owner blocked"
    const val FAILED_TITLE = "Owner failed"
    const val RUNNING_TITLE = "Owner running"

    const val NO_BYTES = "no bytes: wrong port or baud"
    const val NOT_ACKED = "MCU talking, ACK formula wrong"
    const val ACKED = "MCU acknowledged"

    /** [status] null = no owner on this slot, the vendor binder carries the link. */
    fun read(status: McuOwner.Status?, gatewayConnected: Boolean): CarLinkReading {
        if (status == null) {
            return CarLinkReading(
                ok = gatewayConnected,
                title = VENDOR_TITLE,
                detail = if (gatewayConnected) VENDOR_CONNECTED else VENDOR_DISCONNECTED,
            )
        }

        return when (status) {
            McuOwner.Status.Idle -> CarLinkReading(ok = false, title = IDLE_TITLE, detail = IDLE_DETAIL)
            is McuOwner.Status.Blocked -> CarLinkReading(ok = false, title = BLOCKED_TITLE, detail = status.reason)
            is McuOwner.Status.Failed -> CarLinkReading(ok = false, title = FAILED_TITLE, detail = status.reason)
            is McuOwner.Status.Running -> CarLinkReading(
                ok = status.acked,
                title = RUNNING_TITLE,
                detail = "${running(status)} (${counters(status)})",
            )
        }
    }

    /** The one-line verdict from the acceptance checklist, step 5. */
    fun running(status: McuOwner.Status.Running): String = when {
        status.frames == 0L -> NO_BYTES
        !status.acked -> NOT_ACKED
        else -> ACKED
    }

    /** The raw counters, in the order logcat prints them. */
    fun counters(status: McuOwner.Status.Running): String =
        "acked=${status.acked} frames=${status.frames} badChecksum=${status.badChecksum} skipped=${status.skipped}"
}
