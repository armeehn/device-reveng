package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the tyre popup is raised, and the box query the TPMS page sends on open. */
class TpmsAlertTest {

    /** A 0x48 payload decoded as the car sends it: status in p[0], FL pair at p[2] and p[7]. */
    private fun report(status: Int): CanSignal.Tpms {
        val p = byteArrayOf(status.toByte(), 0, 0x96.toByte(), 0x96.toByte(), 0x96.toByte(),
            0x96.toByte(), 0xFE.toByte(), 0x0A, 0x0A, 0x0A, 0x0A, 0)
        return HiworldCanDecoder.decodePayload(0x48, p) as CanSignal.Tpms
    }

    @Test
    fun `warning raises once per rising edge`() {
        val alert = TpmsAlert()

        assertFalse(alert.onReport(report(0x80)))
        assertTrue(alert.onReport(report(0xC0)))
        assertFalse(alert.onReport(report(0xC0)))
        assertFalse(alert.onReport(report(0x80)))
        assertTrue(alert.onReport(report(0xC0)))
    }

    /** Stock hides the normal/abnormal line when TPMS is invalid, so an invalid report never alerts. */
    @Test
    fun `abnormal while invalid does not alert`() {
        assertFalse(TpmsAlert().onReport(report(0x40)))
    }

    /** Stock page `requestInfo`: `sendQToCan(72, 0)` = `03 6A 05 01 48`. */
    @Test
    fun `query asks the box for block 0x48`() {
        assertArrayEquals(intArrayOf(0x03, 0x6A, 0x05, 0x01, 0x48), TpmsAlert.QUERY)
    }

    /** On the owner port the query rides `0D 08 5A A5 ... CK`, CK = sum - 1 = 0xBA. */
    @Test
    fun `owner frame carries the query`() {
        val expected = McuSerial.encode(0x0D, byteArrayOf(0x08, 0x5A, 0xA5.toByte(), 0x03, 0x6A, 0x05, 0x01, 0x48, 0xBA.toByte()))
        assertArrayEquals(expected, McuOwnerProtocol.tpmsQuery())
    }
}
