package com.ripostelabs.car

import org.junit.Assert.assertEquals
import org.junit.Test

class EventTableTest {

    @Test
    fun everyVendorCallHasAReplyShape() {
        assertEquals(VENDOR_CALLS, EventTable.calls.size)
    }

    // The served codes are the vendor ordinals; the generated stub must agree with each.
    @Test
    fun servedCodesNameTheVendorCalls() {
        val served = mapOf(
            EventBinder.SEND_MODE to "sendMode",
            EventBinder.SEND_RADIO_KEY to "sendRadioKey",
            EventBinder.SEND_USER_FREQ to "sendUserFreq",
            EventBinder.SEND_MUTE to "sendMuteState",
            EventBinder.GET_MCU_VER to "getMCUVer",
            EventBinder.GET_SETTING_BOOLEAN to "getSettingBoolean",
            EventBinder.GET_SETTING_FLOAT to "getSettingFloat",
            EventBinder.GET_SETTING_INT to "getSettingInt",
            EventBinder.GET_SETTING_LONG to "getSettingLong",
            EventBinder.GET_SETTING_STRING to "getSettingString",
            EventBinder.GET_VALID_MODE to "getValidMode",
            EventBinder.SEND_BACKLIGHT to "sendBacklight",
            EventBinder.IS_BACK_CAR to "IsBackCarConneted",
            EventBinder.GET_MAIN_VOL to "getMainVolval",
            EventBinder.IS_MUTE_ON to "IsMuteOn",
            EventBinder.SOFTWARE_REBOOT to "sendSoftWareReboot",
        )

        served.forEach { (code, name) -> assertEquals(name, EventTable.calls.getValue(code).name) }
    }

    @Test
    fun replyShapesFollowTheReturnTypes() {
        val shapes = EventTable.calls.values.associate { it.name to it.reply }

        assertEquals(Reply.VOID, shapes["sendMode"])
        assertEquals(Reply.INT, shapes["IsMuteOn"])
        assertEquals(Reply.INT, shapes["getMainVolval"])
        assertEquals(Reply.LONG, shapes["getSettingLong"])
        assertEquals(Reply.FLOAT, shapes["getSettingFloat"])
        assertEquals(Reply.STRING, shapes["getMCUVer"])
        assertEquals(Reply.ARRAY, shapes["getRadioFreqList"])
        assertEquals(Reply.BINDER, shapes["getCameraService"])
    }

    private companion object {
        /** Methods in carlib's IEventService.aidl. */
        const val VENDOR_CALLS = 144
    }
}
