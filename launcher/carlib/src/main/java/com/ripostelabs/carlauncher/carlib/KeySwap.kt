package com.ripostelabs.carlauncher.carlib

/** One swap switch: keys as the car sends them, or traded with their pair. */
enum class SwapMode { NORMAL, SWAPPED }

/**
 * Stock's two swap switches for the CAN key channel (`ZXW_CAN_KEY_EVT`, EvtModel.java:503-508):
 *
 * ```
 *  Sys_updownset = 1   2 NEXT <-> 3 PREV, 7 SEEK_UP <-> 8 SEEK_DOWN   (getUpAndDownCanKey, :1217)
 *  Sys_addsubset = 1  18 VOL_ADD <-> 19 VOL_SUB                       (getVolAddSubCanKey, :1282)
 * ```
 *
 * The CAN channel carries the wheel (0x11), the box panel (0x21) and the knobs (0x22), so a
 * swap also turns a knob the other way. Learned resistive keys skip it in stock
 * (`setMcuKeyEvent` calls `ProcessCanKey` directly), so [KeyRouter] leaves them alone.
 *
 * Applied on top of the car-direction fix in [WheelKey] (#281), never instead of it: NORMAL is
 * what the car does, SWAPPED is the user's choice.
 */
data class KeySwap(
    val prevNext: SwapMode = SwapMode.NORMAL,
    val volume: SwapMode = SwapMode.NORMAL,
) {

    /** A wheel key after the prev/next swap; a hold or double press follows its key, as 7/8 do. */
    fun key(key: WheelKey): WheelKey {
        if (prevNext == SwapMode.NORMAL) {
            return key
        }
        return when (key) {
            WheelKey.NEXT -> WheelKey.PREV
            WheelKey.PREV -> WheelKey.NEXT
            else -> key
        }
    }

    fun gesture(gesture: WheelGesture): WheelGesture {
        val swapped = key(gesture.key)
        if (swapped == gesture.key) {
            return gesture
        }
        return when (gesture) {
            is WheelGesture.Press -> WheelGesture.Press(swapped)
            is WheelGesture.LongPress -> WheelGesture.LongPress(swapped)
            is WheelGesture.DoublePress -> WheelGesture.DoublePress(swapped)
        }
    }

    /** An action off the CAN channel after both swaps. */
    fun action(action: KeyAction): KeyAction = when {
        prevNext == SwapMode.SWAPPED && action == KeyAction.NEXT -> KeyAction.PREV
        prevNext == SwapMode.SWAPPED && action == KeyAction.PREV -> KeyAction.NEXT
        volume == SwapMode.SWAPPED && action == KeyAction.VOLUME_UP -> KeyAction.VOLUME_DOWN
        volume == SwapMode.SWAPPED && action == KeyAction.VOLUME_DOWN -> KeyAction.VOLUME_UP
        else -> action
    }

    companion object {
        val NONE = KeySwap()
    }
}
