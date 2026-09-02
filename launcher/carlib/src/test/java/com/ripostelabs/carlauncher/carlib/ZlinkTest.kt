package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the intents against `eventcenter/manager/ZlinkManage.java` (the gateway's side). */
class ZlinkTest {

    @Test
    fun mainPageDeepLink() {
        assertEquals(
            IntentSpec(
                action = "zjinnova.android.intent.action.ZLINK_MAIN",
                packageName = "com.zjinnova.zlink",
                strings = mapOf("page" to "main", "feature" to "carplay"),
            ),
            Zlink.mainPage("main", "carplay"),
        )
    }

    @Test
    fun specialFunctionCommand() {
        val spec = Zlink.request(Zlink.Feature.SIRI)
        assertEquals("com.zjinnova.zlink", spec.action)
        assertEquals(mapOf("command" to "REQ_SPEC_FUNC_CMD"), spec.strings)
        assertEquals(mapOf("specFuncCode" to 1500), spec.ints)
    }

    /** RAV4-52: every quick action is one REQ_SPEC_FUNC_CMD broadcast carrying its code. */
    @Test
    fun quickActionsAreOneBroadcastEach() {
        val expected = mapOf(
            Zlink.Feature.SIRI to 1500,
            Zlink.Feature.MAPS to 1504,
            Zlink.Feature.PHONE to 1505,
            Zlink.Feature.MUSIC to 1506,
            Zlink.Feature.NOW_PLAYING to 1507,
            Zlink.Feature.HOME to 1508,
        )
        for ((feature, code) in expected) {
            assertEquals(
                IntentSpec(
                    action = "com.zjinnova.zlink",
                    strings = mapOf("command" to "REQ_SPEC_FUNC_CMD"),
                    ints = mapOf("specFuncCode" to code),
                ),
                Zlink.request(feature),
            )
        }
    }

    @Test
    fun openIsTheMainPageDeepLink() {
        assertEquals(Zlink.mainPage("main", "carplay"), Zlink.open())
    }

    @Test
    fun featureCodesMatchTheGateway() {
        assertEquals(1500, Zlink.Feature.SIRI.code)
        assertEquals(1504, Zlink.Feature.MAPS.code)
        assertEquals(1505, Zlink.Feature.PHONE.code)
        assertEquals(1506, Zlink.Feature.MUSIC.code)
        assertEquals(1507, Zlink.Feature.NOW_PLAYING.code)
        assertEquals(1508, Zlink.Feature.HOME.code)
    }

    @Test
    fun projectionActivityFollowsThePhoneLinkType() {
        assertEquals(Zlink.CARPLAY_ACTIVITY, Zlink.projectionActivity(null))
        assertEquals(Zlink.CARPLAY_ACTIVITY, Zlink.projectionActivity(""))
        assertEquals(Zlink.CARPLAY_ACTIVITY, Zlink.projectionActivity("carplay_wireless"))
        assertEquals(Zlink.ANDROID_AUTO_ACTIVITY, Zlink.projectionActivity("auto_wired"))
        assertEquals(Zlink.HICAR_ACTIVITY, Zlink.projectionActivity("hicar_wireless"))
        assertEquals(Zlink.MIRROR_ACTIVITY, Zlink.projectionActivity("airplay_wired"))
        assertEquals(Zlink.MIRROR_ACTIVITY, Zlink.projectionActivity("android_mirror_wireless"))
        assertEquals(Zlink.DLNA_ACTIVITY, Zlink.projectionActivity("dlna_wired"))
    }
}
