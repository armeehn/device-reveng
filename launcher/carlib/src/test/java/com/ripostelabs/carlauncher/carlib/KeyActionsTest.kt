package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The key → action table on Riposte OS 0.2, transcribed from what eventcenter did with each
 * code: `ProcessCanKey` (EventService.java:13021-13110) for the CAN wheel, `onCmdKeyEvent`
 * (`:2401-2699`) for the panel and `setMcuKeyEvent` (McuToArmDataManage.java:616-1090) for a
 * learned resistive key.
 */
class KeyActionsTest {

    @Test
    fun everyCanWheelKeyHasAnAction() {
        val expected = mapOf(
            WheelKey.PREV to KeyAction.PREV,           // 3 → sendMediaKey(88), :13082
            WheelKey.NEXT to KeyAction.NEXT,           // 2 → sendMediaKey(87), :13083
            WheelKey.MODE to KeyAction.MODE,           // 16 → switchMode, :13069
            WheelKey.PLAY_PAUSE to KeyAction.PLAY_PAUSE, // 6 → sendMediaKey(85), :13084
            WheelKey.TALK to KeyAction.TALK,           // 23, :13060
            WheelKey.HANGUP to KeyAction.HANGUP,       // 22, :13123
            WheelKey.RETURN to KeyAction.BACK,         // 85 → sendKeyDownUpSync(4), :13057
            WheelKey.MUTE to KeyAction.MUTE,           // 17 → sendSystemKey(12), :13050
            WheelKey.VOICE to KeyAction.VOICE,         // 116 → startVoice, :13036
        )
        WheelKey.values().forEach { key ->
            assertEquals(key.name, expected.getValue(key), KeyActions.forCan(key))
        }
    }

    @Test
    fun panelKeysFollowOnCmdKeyEvent() {
        assertEquals(KeyAction.PLAY_PAUSE, KeyActions.forPanel(McuOwnerProtocol.Key.PLAY_PAUSE))
        assertEquals(KeyAction.HANGUP, KeyActions.forPanel(McuOwnerProtocol.Key.HANGUP))
        assertEquals(KeyAction.VOICE, KeyActions.forPanel(McuOwnerProtocol.Key.VOICE))
        assertEquals(KeyAction.SETTINGS, KeyActions.forPanel(McuOwnerProtocol.Key.SETUP))
        assertEquals(KeyAction.NAV, KeyActions.forPanel(KeyActions.PANEL_NAV))
    }

    /** VOL+/VOL-/MUTE panel keys are echoed to the MCU by the owner (`08 xx`); no second path. */
    @Test
    fun panelVolumeAndMuteAreTheOwnersEcho() {
        assertNull(KeyActions.forPanel(McuOwnerProtocol.Key.VOLUME_UP))
        assertNull(KeyActions.forPanel(McuOwnerProtocol.Key.VOLUME_DOWN))
        assertNull(KeyActions.forPanel(McuOwnerProtocol.Key.MUTE))
        assertNull(KeyActions.forPanel(McuOwnerProtocol.Key.POWER))
    }

    /** The `CAR_KEY` twins already reach the key pump through `SwcFallback.mcuKey`. */
    @Test
    fun panelCarKeyTwinsAreNotRoutedTwice() {
        for (code in listOf(
            McuOwnerProtocol.Key.NEXT, McuOwnerProtocol.Key.PREV, McuOwnerProtocol.Key.MENU,
            McuOwnerProtocol.Key.RETURN, McuOwnerProtocol.Key.MODE, McuOwnerProtocol.Key.TALK,
            McuOwnerProtocol.Key.RADIO,
        )) {
            assertNull("code $code", KeyActions.forPanel(code))
            assertEquals("code $code", true, SwcFallback.mcuKey(code) != null)
        }
    }

    /** `72` codes 141..155 are learned wheel slots 0..14 (`onMcuToWheelCustomKey`, :431-434). */
    @Test
    fun learnedSlotCodes() {
        assertEquals(0, KeyActions.learnedSlotOf(141))
        assertEquals(14, KeyActions.learnedSlotOf(155))
        assertNull(KeyActions.learnedSlotOf(140))
        assertNull(KeyActions.learnedSlotOf(156))
        assertNull(KeyActions.learnedSlotOf(McuOwnerProtocol.Key.NEXT))
    }

    @Test
    fun learnedFunctionsFollowSetMcuKeyEvent() {
        assertEquals(KeyAction.VOLUME_UP, KeyActions.forFunction(WheelFunction.VOLUME_UP))     // case 3
        assertEquals(KeyAction.VOLUME_DOWN, KeyActions.forFunction(WheelFunction.VOLUME_DOWN)) // case 4
        assertEquals(KeyAction.HANGUP, KeyActions.forFunction(WheelFunction.HANG_UP))         // case 0
        assertEquals(KeyAction.RADIO, KeyActions.forFunction(WheelFunction.FM))               // case 17
        assertEquals(KeyAction.PLAY_PAUSE, KeyActions.forFunction(WheelFunction.OK))          // case 18
        assertEquals(KeyAction.BACK, KeyActions.forFunction(WheelFunction.BACK))              // case 22
        assertEquals(KeyAction.HOME, KeyActions.forFunction(WheelFunction.HOME))              // case 23
        assertEquals(KeyAction.MODE, KeyActions.forFunction(WheelFunction.MODE))              // case 26
        assertEquals(KeyAction.MUTE, KeyActions.forFunction(WheelFunction.MUTE))              // case 27
        assertEquals(KeyAction.NAV, KeyActions.forFunction(WheelFunction.NAVI))               // case 28
        assertEquals(KeyAction.NEXT, KeyActions.forFunction(WheelFunction.NEXT))              // case 29
        assertEquals(KeyAction.PREV, KeyActions.forFunction(WheelFunction.PREV))              // case 30
        assertEquals(KeyAction.TALK, KeyActions.forFunction(WheelFunction.TALK))              // case 31
        assertEquals(KeyAction.OPEN_MEDIA, KeyActions.forFunction(WheelFunction.MUSIC))       // case 34
        assertEquals(KeyAction.SETTINGS, KeyActions.forFunction(WheelFunction.SETTINGS))      // case 36
        assertEquals(KeyAction.VOICE, KeyActions.forFunction(WheelFunction.VOICE))            // case 38
        assertNull(KeyActions.forFunction(WheelFunction.CAMERA_360))
        assertNull(KeyActions.forFunction(WheelFunction.POWER))
    }
}
