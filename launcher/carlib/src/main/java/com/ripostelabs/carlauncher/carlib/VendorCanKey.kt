package com.ripostelabs.carlauncher.carlib

/**
 * The broadcast the CAN app uses to hand a decoded wheel key to the gateway, usable by anyone:
 * `CanDataParseBase.sendMCUKey` → `sendBroadcastCanKeyExtra(ZXW_CAN_KEY_EVT, code)`
 * (`CanDataParseBase.java:1078,1518-1536`), received unpermissioned at `EvtModel.java:492-511`
 * and acted on by `EventService.ProcessCanKey` (`:13021-13110`). Sending it ourselves makes the
 * gateway behave exactly as if the wheel had sent that key. UNVERIFIED on the car.
 */
object VendorCanKey {

    const val ACTION = "com.choiceway.eventcenter.EventUtils.ZXW_CAN_KEY_EVT"
    const val EXTRA_CODE = "com.choiceway.eventcenter.EventUtils.ZXW_CAN_KEY_EVT_EXTRA"

    /** The gateway's `ProcessCanKey` branch for [key]'s MCU code, e.g. VOICE → `startVoice()`. */
    fun press(key: WheelKey): IntentSpec = IntentSpec(
        action = ACTION,
        ints = mapOf(EXTRA_CODE to key.mcuKey),
    )
}
