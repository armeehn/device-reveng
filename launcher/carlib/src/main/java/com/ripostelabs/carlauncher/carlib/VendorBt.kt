package com.ripostelabs.carlauncher.carlib

/**
 * Minimal control surface for the vendor bt module (`com.szchoiceway.btsuite`), the inbound
 * half of the contract whose outbound half is [VendorBtDecode].
 *
 * ⚠ UNVERIFIED on the car. Every action and extra below is transcribed from the decompile
 * (`btsuite/BTService.java:1219-1254` registers them, `:1496-1530` / `:1338-1341` / `:1801-1836`
 * handle them), and btsuite registers its receiver without a permission, so a normal app
 * should reach it. Whether the module then acts has not been observed.
 *
 * ```
 *  launcher ──broadcast──▶ btsuite (uid system) ──serial──▶ BT module
 *     zxw_bluetooth_contral_action  + int zxw_bluetooth_contral_key
 *        5  dial       (+ str zxw_bluetooth_contral_key_value_str = number)
 *       10  connect    (+ str = MAC)          11  disconnect
 *        3  audio to phone   4  audio to car   7  query power   8  re-send device name
 *     HBCP_HANGUP_EVENT                       hang up
 *     MCU_KEY_INFOR + int MCU_KEY_VALUE       22 hang up / 23 answer
 * ```
 *
 * `MCU_KEY_INFOR` is the same unprotected action [CarEvents] consumes as a panel-key fallback;
 * codes 22/23 are not CAR_KEY codes, so our own receiver drops them ([SwcFallback.mcuKey]).
 *
 * Nothing here is wired to UI. The builders are pure ([IntentSpec]) so a test can pin them.
 */
object VendorBt {

    const val ACTION_CONTROL = "zxw_bluetooth_contral_action"
    const val EXTRA_CONTROL_KEY = "zxw_bluetooth_contral_key"
    const val EXTRA_CONTROL_VALUE = "zxw_bluetooth_contral_key_value_str"

    /** `BTService.java:1496-1530`. */
    const val CONTROL_AUDIO_TO_PHONE = 3
    const val CONTROL_AUDIO_TO_CAR = 4
    const val CONTROL_DIAL = 5
    const val CONTROL_QUERY_POWER = 7
    const val CONTROL_SEND_DEVICE_NAME = 8
    const val CONTROL_CONNECT = 10
    const val CONTROL_DISCONNECT = 11

    const val ACTION_HANGUP = "com.szchoiceway.btsuite.HBCP_HANGUP_EVENT"

    /** `MCU_KEY_INFOR` codes btsuite answers (`BTService.java:1801-1836`). */
    const val MCU_KEY_HANG_UP = 22
    const val MCU_KEY_ANSWER = 23

    fun dial(number: String): IntentSpec = control(CONTROL_DIAL, number)

    fun hangUp(): IntentSpec = IntentSpec(ACTION_HANGUP)

    fun answer(): IntentSpec = mcuKey(MCU_KEY_ANSWER)

    /** [mac] as the module wants it, e.g. `AA:BB:CC:DD:EE:FF` — format UNVERIFIED. */
    fun connect(mac: String): IntentSpec = control(CONTROL_CONNECT, mac)

    fun disconnect(): IntentSpec = control(CONTROL_DISCONNECT)

    /** Ask btsuite to re-broadcast `HBCP_EVT_CUR_CONNECTED_DEVICE_NAME` (seeds a fresh UI). */
    fun requestDeviceName(): IntentSpec = control(CONTROL_SEND_DEVICE_NAME)

    private fun control(key: Int, value: String? = null): IntentSpec = IntentSpec(
        action = ACTION_CONTROL,
        ints = mapOf(EXTRA_CONTROL_KEY to key),
        strings = if (value == null) emptyMap() else mapOf(EXTRA_CONTROL_VALUE to value),
    )

    private fun mcuKey(code: Int): IntentSpec = IntentSpec(
        action = CarEvents.MCU_KEY_INFOR_ACTION,
        ints = mapOf(CarEvents.EXTRA_MCU_KEY_VALUE to code),
    )
}
