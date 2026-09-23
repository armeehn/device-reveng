package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One router on the owner path: `72` panel codes, `72` learned-slot codes, `74` resistive
 * edges and the CAN box's 0x11 volume ids all come out as [KeyAction]s.
 */
class KeyRouterTest {

    private val out = mutableListOf<KeyAction>()
    private var map = WheelKeyMap.EMPTY
    private val router = KeyRouter(map = { map }, emit = out::add)

    private fun panel(code: Int, status: Int = 1) =
        router.onPanelKey(McuOwnerProtocol.PanelKey(code = code, status = status))

    /** Relay payload for cmd 0x11: key id at p[2], held flag at p[3] (bArr[4] / bArr[5]). */
    private fun basicStatus(id: Int, held: Boolean): CanSignal.BasicStatus {
        val p = ByteArray(8)
        p[2] = id.toByte()
        p[3] = if (held) 1 else 0
        return HiworldCanDecoder.decodePayload(0x11, p) as CanSignal.BasicStatus
    }

    private fun can(id: Int, held: Boolean) = router.onCanSignal(basicStatus(id, held), 0L)

    @Test
    fun panelPlayPauseIsPlayPause() {
        panel(McuOwnerProtocol.Key.PLAY_PAUSE)
        assertEquals(listOf(KeyAction.PLAY_PAUSE), out)
    }

    @Test
    fun panelVolumeIsNotRoutedTwice() {
        panel(McuOwnerProtocol.Key.VOLUME_UP)
        assertTrue(out.isEmpty())
    }

    /** `72 8D 01`: code 141 is learned slot 0; the map says what it means. */
    @Test
    fun learnedPanelCodeResolvesThroughTheMap() {
        map = WheelKeyMap.EMPTY.with(0, WheelFunction.VOLUME_UP)
        panel(141, status = 1)
        assertEquals(listOf(KeyAction.VOLUME_UP), out)
    }

    /** The press edge acts; the release is silent (UNVERIFIED which edges the MCU sends). */
    @Test
    fun learnedPanelCodeReleaseIsSilent() {
        map = WheelKeyMap.EMPTY.with(0, WheelFunction.VOLUME_UP)
        panel(141, status = 0)
        assertTrue(out.isEmpty())
    }

    @Test
    fun unlearnedSlotIsSilent() {
        panel(141, status = 1)
        assertTrue(out.isEmpty())
    }

    @Test
    fun wheelKeyDownResolvesThroughTheMap() {
        map = WheelKeyMap.EMPTY.with(2, WheelFunction.NEXT)
        router.onWheelKey(McuOwnerProtocol.WheelKey(slot = 2, down = true, voltage = 90))
        router.onWheelKey(McuOwnerProtocol.WheelKey(slot = 2, down = false, voltage = 0))
        assertEquals(listOf(KeyAction.NEXT), out)
    }

    @Test
    fun canPressMapsTheKey() {
        router.onCanPress(WheelKey.RETURN)
        assertEquals(listOf(KeyAction.BACK), out)
    }

    /** Volume off the 0x11 frames: a short hold is one step on release (`HiworldCanParseToyota.java:849-856`). */
    @Test
    fun shortCanVolumeHoldStepsOnceOnRelease() {
        repeat(3) { can(KeyRouter.CAN_VOLUME_UP, true) }
        assertTrue(out.isEmpty())
        can(KeyRouter.CAN_VOLUME_UP, false)
        assertEquals(listOf(KeyAction.VOLUME_UP), out)
    }

    /** Past the 5th held frame every frame is a step and the release adds none (`:831-848`). */
    @Test
    fun longCanVolumeHoldRepeatsPerFrame() {
        repeat(8) { can(KeyRouter.CAN_VOLUME_DOWN, true) }
        assertEquals(List(3) { KeyAction.VOLUME_DOWN }, out)
        can(KeyRouter.CAN_VOLUME_DOWN, false)
        assertEquals(3, out.size)
    }

    /** The other ids are the gesture engine's; the router leaves them alone here. */
    @Test
    fun otherCanIdsAreNotVolume() {
        can(12, true)
        can(12, false)
        assertTrue(out.isEmpty())
    }
}
