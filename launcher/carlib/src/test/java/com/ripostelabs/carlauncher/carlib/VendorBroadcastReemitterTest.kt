package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendor's broadcasts, replayed from owner events: which action, which extras, and that the
 * change-only ones do not repeat. Nothing here touches `android.content.Intent`.
 */
class VendorBroadcastReemitterTest {

    private val sent = mutableListOf<IntentSpec>()

    private val reemitter = VendorBroadcastReemitter { sent += it }

    private fun sys(
        reverse: Boolean = false,
        acc: Boolean = false,
        illumination: Boolean = false,
        brake: Boolean = true,
    ) = McuOwnerProtocol.SysEvent(
        disc = false, usb = false, rightTurn = false, illumination = illumination, brake = brake,
        reverse = reverse, accLine = acc, mcan = false, startStop = false, hdmi = false, leftTurn = false,
    )

    private fun actions() = sent.map { it.action }

    @Test
    fun reverseEdgeSendsOneStartThenOneEnd() {
        reemitter.onSysEvent(sys(reverse = true))
        reemitter.onSysEvent(sys(reverse = true))
        reemitter.onSysEvent(sys(reverse = true))
        assertEquals(listOf(CarEvents.MCU_MSG_BACKCAR_START), actions())

        reemitter.onSysEvent(sys(reverse = false))
        assertEquals(listOf(CarEvents.MCU_MSG_BACKCAR_START, CarEvents.MCU_MSG_BACKCAR_END), actions())
    }

    @Test
    fun bootBaselineIsSilentWhenNothingMoved() {
        reemitter.onSysEvent(sys())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun accCarriesIntExtraOnChangeOnly() {
        reemitter.onSysEvent(sys(acc = true))
        reemitter.onSysEvent(sys(acc = true))
        reemitter.onSysEvent(sys(acc = false))

        assertEquals(2, sent.size)
        assertEquals(CarEvents.ACTION_ACC_OPEN_CLOSE_EVT, sent[0].action)
        assertEquals(CarEvents.ACC_STATUS_ON, sent[0].ints[CarEvents.EXTRA_ACC_STATUS])
        assertEquals(CarEvents.ACC_STATUS_SLEEP, sent[1].ints[CarEvents.EXTRA_ACC_STATUS])
    }

    @Test
    fun lampStatusOnIlluminationChangeHasNoExtras() {
        reemitter.onSysEvent(sys(illumination = true))
        reemitter.onSysEvent(sys(illumination = true))

        assertEquals(listOf(CarEvents.LAMP_STATUS), actions())
        assertTrue(sent[0].ints.isEmpty() && sent[0].strings.isEmpty() && sent[0].booleans.isEmpty())
    }

    /** The vendor boots with the brake line "connected"; releasing it is the first edge. */
    @Test
    fun brakeEdgeFromBootBaseline() {
        reemitter.onSysEvent(sys(brake = true))
        reemitter.onSysEvent(sys(brake = false))
        assertEquals(listOf(CarEvents.MCU_MSG_BRAKE_EVT), actions())
    }

    @Test
    fun mailVolRawByteCarriesMuteBit() {
        reemitter.onMainVolume(McuOwnerProtocol.MainVolume(level = 17, silent = false))
        reemitter.onMute(McuOwnerProtocol.Mute(muted = true, silent = true))
        reemitter.onMainVolume(McuOwnerProtocol.MainVolume(level = 17, silent = false))

        assertEquals(3, sent.size)
        sent.forEach { assertEquals(CarEvents.MCU_MSG_MAIL_VOL, it.action) }
        assertEquals(17, sent[0].ints[CarEvents.EXTRA_MAIL_VOL_VAL])
        assertEquals(true, sent[0].booleans[CarEvents.EXTRA_SHOW_VOL_WND])
        assertEquals(0x80 or 17, sent[1].ints[CarEvents.EXTRA_MAIL_VOL_VAL])
        assertEquals(false, sent[1].booleans[CarEvents.EXTRA_SHOW_VOL_WND])
        assertEquals(0x80 or 17, sent[2].ints[CarEvents.EXTRA_MAIL_VOL_VAL])
    }

    @Test
    fun fanOutReachesEveryListener() {
        val seen = mutableListOf<String>()
        val a = object : McuOwner.Listener {
            override fun onKey(key: Int) { seen += "a$key" }
        }
        val b = object : McuOwner.Listener {
            override fun onKey(key: Int) { seen += "b$key" }
        }

        McuOwner.FanOut(a, b).onKey(7)
        assertEquals(listOf("a7", "b7"), seen)
    }
}
