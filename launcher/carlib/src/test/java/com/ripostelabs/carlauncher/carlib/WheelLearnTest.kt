package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stock learn handshake as `CarWheelView.java` drives it through `sendWheelKey(n)`
 * (`07 n`, EventService.java:6369-6375) and reads the MCU's `74` capture echo
 * (`:2847-2859`) and `88` learned mask (`:3060-3068`).
 */
class WheelLearnTest {

    private val sent = mutableListOf<ByteArray>()
    private val saved = mutableListOf<WheelKeyMap>()

    private fun learn(initial: WheelKeyMap = WheelKeyMap.EMPTY) =
        WheelLearn(send = sent::add, onMap = saved::add, initial = initial)

    private fun frame(code: Int) = McuOwnerProtocol.wheelLearn(code).toList()

    private fun sentFrames() = sent.map { it.toList() }

    private fun capture(slot: Int, down: Boolean, voltage: Int = 0) =
        McuOwnerProtocol.WheelKey(slot = slot, down = down, voltage = voltage)

    /** `enterStudyMode` is one frame, `07 70` (`CarWheelView.java:366-368`). */
    @Test
    fun enterSendsTheLearnModeFrame() {
        val l = learn()
        l.enter()
        assertEquals(listOf(frame(WheelKeyMap.LEARN_ENTER)), sentFrames())
        assertEquals(WheelLearn.Phase.ARMED, l.state.value.phase)
    }

    /** The lowest slot no learned function uses is the one taught (`:81-93`). */
    @Test
    fun learnPicksTheLowestFreeSlot() {
        val l = learn(WheelKeyMap.EMPTY.with(0, WheelFunction.NEXT).with(2, WheelFunction.PREV))
        l.enter()
        sent.clear()

        assertTrue(l.learn(WheelFunction.MODE))
        assertEquals(listOf(frame(1)), sentFrames())
        assertEquals(1 to WheelFunction.MODE, l.state.value.pending)
        assertEquals(WheelLearn.Phase.WAITING, l.state.value.phase)
    }

    /** A function already in the map is not re-taught: the click is ignored (`:74-77`). */
    @Test
    fun learnedFunctionIsNotTaughtTwice() {
        val l = learn(WheelKeyMap.EMPTY.with(0, WheelFunction.NEXT))
        l.enter()
        sent.clear()

        assertFalse(l.learn(WheelFunction.NEXT))
        assertTrue(sent.isEmpty())
    }

    /** Fifteen slots taken: the app falls back to slot 0 rather than refusing (`:96-99`). */
    @Test
    fun allSlotsTakenFallsBackToSlotZero() {
        var map = WheelKeyMap.EMPTY
        WheelFunction.values().take(WheelKeyMap.SLOT_MAX + 1).forEachIndexed { slot, f -> map = map.with(slot, f) }
        val l = learn(map)
        l.enter()
        sent.clear()

        assertTrue(l.learn(WheelFunction.EXTERIOR_CAMERA))
        assertEquals(listOf(frame(0)), sentFrames())
    }

    /** Learning without entering first is refused: the MCU is not listening (`:366`, `:102`). */
    @Test
    fun learnBeforeEnterIsRefused() {
        val l = learn()
        assertFalse(l.learn(WheelFunction.NEXT))
        assertTrue(sent.isEmpty())
    }

    /** `74 slot <non-zero>` is the capture echo: the pending function takes the slot (`:176-183`). */
    @Test
    fun captureEchoStoresTheSlotAndPersists() {
        val l = learn()
        l.enter()
        l.learn(WheelFunction.NEXT)

        l.onWheelKey(capture(slot = 0, down = true, voltage = 128))

        val s = l.state.value
        assertEquals(WheelFunction.NEXT, s.map.functionOf(0))
        assertEquals(WheelLearn.Phase.ARMED, s.phase)
        assertNull(s.pending)
        assertEquals(WheelLearn.Result.Learned(0, WheelFunction.NEXT, 128), s.lastResult)
        assertEquals(listOf(s.map), saved)
    }

    /** `74 slot 00` is a failed capture: nothing is stored (`:176-178`). */
    @Test
    fun failedCaptureLeavesTheMapAlone() {
        val l = learn()
        l.enter()
        l.learn(WheelFunction.NEXT)

        l.onWheelKey(capture(slot = 0, down = false))

        val s = l.state.value
        assertTrue(s.map.isEmpty)
        assertEquals(WheelLearn.Result.Failed(0, WheelFunction.NEXT), s.lastResult)
        assertEquals(WheelLearn.Phase.ARMED, s.phase)
        assertTrue(saved.isEmpty())
    }

    /** A `74` while nothing is pending is a normal key press, not a capture. */
    @Test
    fun captureOutsideWaitingIsIgnored() {
        val l = learn(WheelKeyMap.EMPTY.with(0, WheelFunction.NEXT))
        l.enter()

        l.onWheelKey(capture(slot = 3, down = true))

        assertNull(l.state.value.lastResult)
        assertNull(l.state.value.map.functionOf(3))
        assertTrue(saved.isEmpty())
    }

    /** The `88` mask is the MCU's truth: slots it does not hold leave the map (`:225-240`). */
    @Test
    fun learnedMaskPrunesSlotsTheMcuDoesNotHold() {
        val l = learn(WheelKeyMap.EMPTY.with(0, WheelFunction.NEXT).with(2, WheelFunction.PREV))

        l.onWheelState(0b0001)

        val s = l.state.value
        assertEquals(0b0001, s.learnedMask)
        assertEquals(WheelFunction.NEXT, s.map.functionOf(0))
        assertNull(s.map.functionOf(2))
        assertEquals(listOf(s.map), saved)
    }

    /** Save is `07 72`; exit is save then `07 71` (`:337`, `:370-373`). */
    @Test
    fun saveAndExitFrames() {
        val l = learn()
        l.enter()
        sent.clear()

        l.save()
        assertEquals(listOf(frame(WheelKeyMap.LEARN_SAVE)), sentFrames())

        sent.clear()
        l.exit()
        assertEquals(listOf(frame(WheelKeyMap.LEARN_SAVE), frame(WheelKeyMap.LEARN_EXIT)), sentFrames())
        assertEquals(WheelLearn.Phase.IDLE, l.state.value.phase)
    }

    /** Clear is `07 73` and empties the map on our side too (`:341-349`). */
    @Test
    fun clearDropsEveryLearnedKey() {
        val l = learn(WheelKeyMap.EMPTY.with(0, WheelFunction.NEXT))
        l.enter()
        sent.clear()

        l.clear()

        assertEquals(listOf(frame(WheelKeyMap.LEARN_CLEAR)), sentFrames())
        assertTrue(l.state.value.map.isEmpty)
        assertEquals(listOf(WheelKeyMap.EMPTY), saved)
    }

    /** High-Z is `07 74`, low-Z `07 75` (`:314-327`). */
    @Test
    fun impedanceFrames() {
        val l = learn()
        l.setImpedance(WheelLearn.Impedance.HIGH)
        l.setImpedance(WheelLearn.Impedance.LOW)
        assertEquals(
            listOf(frame(WheelKeyMap.LEARN_HIGH_IMPEDANCE), frame(WheelKeyMap.LEARN_LOW_IMPEDANCE)),
            sentFrames(),
        )
    }

    /** The store loads after the engine exists; a load replaces the map and keeps the phase. */
    @Test
    fun loadReplacesTheMapAndKeepsThePhase() {
        val l = learn()
        l.enter()
        l.load(WheelKeyMap.EMPTY.with(4, WheelFunction.MUTE))
        assertEquals(WheelFunction.MUTE, l.state.value.map.functionOf(4))
        assertEquals(WheelLearn.Phase.ARMED, l.state.value.phase)
        assertTrue(saved.isEmpty())
    }

    /** The reading is an 8-bit ADC over 3.3 V (`CarWheelView.java:162`). */
    @Test
    fun voltsScaleTheAdcByte() {
        assertEquals(3.3f, WheelLearn.volts(255), 0.001f)
        assertEquals(1.656f, WheelLearn.volts(128), 0.001f)
        assertEquals(0f, WheelLearn.volts(0), 0f)
    }
}
