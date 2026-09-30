package com.ripostelabs.carlauncher.carlib

/**
 * Decides when a 0x48 tyre report raises the tyre popup: once on each rising edge of the car's
 * own "pressure abnormal" bit, and only while the car says its TPMS is valid.
 *
 *     report  valid abnormal ──▶ popup?
 *     1       1     0        ──▶ no
 *     2       1     1        ──▶ yes   (rising edge)
 *     3       1     1        ──▶ no    (already raised; the box repeats the report)
 *     4       1     0        ──▶ no    (cleared, re-armed)
 *
 * No threshold of ours: the bit is the car's verdict. Stock shows it only when valid
 * (`HiworldToyotaTPMSUILandscapeDefault.java:112-121`), so an invalid report never alerts.
 */
class TpmsAlert {

    private var raised = false

    /** True when this report should raise the popup. */
    fun onReport(report: CanSignal.Tpms): Boolean {
        val abnormal = report.valid && report.abnormal
        val rises = abnormal && !raised
        raised = abnormal
        return rises
    }

    companion object {
        /** `03 6A 05 01 48`: the stock tyre page's `sendQToCan(72, 0)` on open. */
        val QUERY = intArrayOf(0x03, 0x6A, 0x05, 0x01, 0x48)
    }
}
