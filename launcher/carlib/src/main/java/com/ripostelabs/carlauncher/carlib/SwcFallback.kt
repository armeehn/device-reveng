package com.ripostelabs.carlauncher.carlib

/**
 * Decode the UNPROTECTED `MCU_KEY_INFOR` key broadcast into the same canonical form as the
 * protected `STEER_WHEEL_INFOR` path, so a NON-ROOT install still has wheel control.
 *
 * `MCU_KEY_INFOR` is where every key the MCU reports ends up — panel keys, learned SWC keys and
 * CAN-box keys alike (`EventService.java:8948-8967` → `EventUtils.sendKeyEventBroadcast`,
 * `EventUtils.java:2147-2154`). Its int extra is a code from the vendor `MCU_KEY_*` table
 * (`EventUtils.java:1458-1656`), NOT a `CAR_KEY_*` index. There is no edge encoding: one
 * broadcast per press, and a long press is its own code. The RAV4 CAN box maps its wheel to these
 * codes at `HiworldCanParseToyota.java:853-885`.
 *
 * `ACTION_HOST_MCU_BUTTON_KEY` is deliberately NOT decoded here any more: it is the volume relay
 * to an original-car amplifier (`HostKeyWord` 1..4, `HostKeyStatus` 1 down / 0 up), only sent
 * when that routing is configured (`EventService.java:4224-4289`). It was never a wheel path.
 *
 * All functions are pure so a unit test reaches them without a Context.
 */
internal object SwcFallback {

    /** A decoded key edge in the canonical `STEER_WHEEL_INFOR` form. */
    data class Edge(val keyIndex: Int, val down: Boolean)

    // ---- MCU_KEY_* codes (EventUtils.java:1458-1656). Codes with no CAR_KEY twin are listed
    // so a reader can see they were considered, not overlooked.
    const val MCU_KEY_POWER = 1
    const val MCU_KEY_NEXT = 2
    const val MCU_KEY_PREV = 3
    const val MCU_KEY_PLAYPAUSE = 6
    /** The vendor's HOME key is named MENU. */
    const val MCU_KEY_MENU = 9
    const val MCU_KEY_MODE = 16
    const val MCU_KEY_MUTE = 17
    const val MCU_KEY_VOL_ADD = 18
    const val MCU_KEY_VOL_SUB = 19
    const val MCU_KEY_HANGUP = 22
    const val MCU_KEY_TALK = 23
    /** The panel's RADIO key (0x36); its CAR_KEY twin opens the tuner screen. */
    const val MCU_KEY_RADIO = 54
    /** The vendor's BACK key. */
    const val MCU_KEY_RETURN = 85
    const val MCU_KEY_TASK_LIST = 113
    /** Voice assistant ("shengkong"). */
    const val MCU_KEY_VOICE = 116

    /**
     * `MCU_KEY_INFOR`: `MCU_KEY_VALUE` (int MCU_KEY code), NO press state on this path — the
     * caller synthesises a complete down+up tap from the one broadcast.
     *
     * Only codes with a `CAR_KEY_*` meaning map. Volume and mute are left out on purpose: the
     * gateway already applies them to the amplifier before broadcasting, so acting on them here
     * would double the step. Play/pause, hang-up, voice, task list and power have no CAR_KEY twin.
     *
     * @return the canonical CAR_KEY index, or null for a code we do not act on.
     */
    fun mcuKey(value: Int?): Int? = when (value) {
        MCU_KEY_MENU -> CarEvents.CAR_KEY_HOME
        MCU_KEY_RETURN -> CarEvents.CAR_KEY_BACK
        MCU_KEY_NEXT -> CarEvents.CAR_KEY_NEXT
        MCU_KEY_PREV -> CarEvents.CAR_KEY_PREV
        MCU_KEY_TALK -> CarEvents.CAR_KEY_PHONE
        MCU_KEY_MODE -> CarEvents.CAR_KEY_MEDIA
        MCU_KEY_RADIO -> CarEvents.CAR_KEY_RADIO
        else -> null
    }

    /**
     * Riposte OS 0.2: the extras `onCmdWheelEvent` would have broadcast for a `74` frame
     * (`EventService.java:2847-2859`): LPARAM = slot + 1, WPARAM 3 down / 4 up, VOLTAGE as read.
     * Built here so the owner path and the broadcast path dedupe and dispatch as one.
     */
    fun wheelInts(key: McuOwnerProtocol.WheelKey): Map<String, Int> = mapOf(
        CarEvents.EXTRA_SWC_LPARAM to key.slot + 1,
        CarEvents.EXTRA_SWC_WPARAM to
            if (key.down) CarEvents.SWC_STATE_DOWN else CarEvents.SWC_STATE_UP,
        CarEvents.EXTRA_SWC_VOLTAGE to key.voltage,
    )

    /**
     * The canonical int-extra map an edge dedupes and dispatches under. Same key set as
     * [CarEvents.swcDedupeInts] keeps for the protected path — that identity is what lets
     * [ProtectedEventDedupe] drop the duplicate when the protected capture (root/system) and
     * this unprotected delivery co-arrive on a rooted unit.
     */
    fun canonicalInts(edge: Edge): Map<String, Int> = mapOf(
        CarEvents.EXTRA_SWC_LPARAM to edge.keyIndex,
        CarEvents.EXTRA_SWC_WPARAM to
            if (edge.down) CarEvents.SWC_STATE_DOWN else CarEvents.SWC_STATE_UP,
    )
}
