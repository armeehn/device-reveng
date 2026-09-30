package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stock's two swaps on the CAN key channel (EvtModel.java:503-508): `Sys_updownset` trades
 * MCU keys 2/3 (next/prev) and 7/8 (seek), `Sys_addsubset` trades 18/19 (volume).
 */
class KeySwapTest {

    private val out = mutableListOf<KeyAction>()
    private var swap = KeySwap.NONE
    private val router = KeyRouter(map = { WheelKeyMap.EMPTY }, emit = out::add, swap = { swap })

    private fun frame(opcode: Int, vararg p: Int): CanSignal =
        HiworldCanDecoder.decodePayload(opcode, p.map { it.toByte() }.toByteArray())

    /** Relay payload for cmd 0x11: key id at p[2], held flag at p[3]. */
    private fun wheel(id: Int, held: Boolean): CanSignal {
        val p = ByteArray(8)
        p[2] = id.toByte()
        p[3] = if (held) 1 else 0
        return HiworldCanDecoder.decodePayload(0x11, p)
    }

    @Test
    fun noneLeavesEveryKeyAlone() {
        assertEquals(WheelKey.NEXT, KeySwap.NONE.key(WheelKey.NEXT))
        assertEquals(KeyAction.VOLUME_UP, KeySwap.NONE.action(KeyAction.VOLUME_UP))
    }

    @Test
    fun prevNextSwapTradesTheWheelKeysAndTheirHolds() {
        val s = KeySwap(prevNext = SwapMode.SWAPPED)

        assertEquals(WheelKey.PREV, s.key(WheelKey.NEXT))
        assertEquals(WheelKey.NEXT, s.key(WheelKey.PREV))
        assertEquals(WheelGesture.LongPress(WheelKey.PREV), s.gesture(WheelGesture.LongPress(WheelKey.NEXT)))
        assertEquals(WheelKey.MODE, s.key(WheelKey.MODE))
        assertEquals(KeyAction.VOLUME_UP, s.action(KeyAction.VOLUME_UP))
    }

    @Test
    fun volumeSwapTradesVolumeOnly() {
        val s = KeySwap(volume = SwapMode.SWAPPED)

        assertEquals(KeyAction.VOLUME_DOWN, s.action(KeyAction.VOLUME_UP))
        assertEquals(KeyAction.VOLUME_UP, s.action(KeyAction.VOLUME_DOWN))
        assertEquals(KeyAction.NEXT, s.action(KeyAction.NEXT))
        assertEquals(WheelKey.NEXT, s.key(WheelKey.NEXT))
    }

    /** The box knobs ride the same channel in stock, so a swap turns them the other way. */
    @Test
    fun swapsFlipTheBoxKnobs() {
        swap = KeySwap(prevNext = SwapMode.SWAPPED, volume = SwapMode.SWAPPED)

        // Both knobs turn up by 2 detents from rest.
        router.onCanSignal(frame(0x22, 0x01, 0x02), atMs = 0L)
        router.onCanSignal(frame(0x22, 0x02, 0x02), atMs = 0L)

        assertEquals(List(2) { KeyAction.VOLUME_DOWN } + List(2) { KeyAction.PREV }, out)
    }

    /** Wheel volume (0x11 ids 1/2) is stock's 18/19 on the CAN channel, so it swaps too. */
    @Test
    fun volumeSwapFlipsTheWheelVolumeKeys() {
        swap = KeySwap(volume = SwapMode.SWAPPED)

        router.onCanSignal(wheel(KeyRouter.CAN_VOLUME_UP, held = true), atMs = 0L)
        router.onCanSignal(wheel(KeyRouter.CAN_VOLUME_UP, held = false), atMs = 0L)

        assertEquals(listOf(KeyAction.VOLUME_DOWN), out)
    }

    /** Learned resistive keys skip the CAN channel in stock (setMcuKeyEvent), so no swap. */
    @Test
    fun learnedKeysAreNotSwapped() {
        swap = KeySwap(prevNext = SwapMode.SWAPPED, volume = SwapMode.SWAPPED)
        val learned = WheelKeyMap.EMPTY.with(0, WheelFunction.NEXT)
        val r = KeyRouter(map = { learned }, emit = out::add, swap = { swap })

        r.onWheelKey(McuOwnerProtocol.WheelKey(slot = 0, down = true, voltage = 0))

        assertEquals(listOf(KeyAction.NEXT), out)
    }
}
