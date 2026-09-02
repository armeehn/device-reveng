package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * HVAC buttons the CAN app accepts from any process.
 *
 * Values are canbus2 `CanUtils.CAR_AIR_KEY_*` (`CB/CanUtils.java:52-180`). The CAN app turns
 * them into the frame its configured CAN box wants (Hiworld Toyota:
 * `HiworldCanParseToyota.java:1784-1941`), so the launcher never builds a serial frame.
 */
enum class ClimateButton(val keyValue: Int) {
    POWER(0),
    FAN_UP(1),
    FAN_DOWN(2),
    LEFT_TEMP_UP(3),
    LEFT_TEMP_DOWN(4),
    RIGHT_TEMP_UP(5),
    RIGHT_TEMP_DOWN(6),
    AUTO(7),
    AC(8),
    AC_MAX(9),
    DUAL(10),
    RECIRCULATE(12),
    FRONT_DEFROST(15),
    REAR_DEFROST(16),
    LEFT_SEAT_COOL(17),
    LEFT_SEAT_HEAT(18),
    RIGHT_SEAT_COOL(19),
    RIGHT_SEAT_HEAT(20),
    MODE(21),
    ECO(40),
    REAR_LOCK(49),
    SYNC(66),
}

/** The one broadcast a button press becomes; pure data so tests can check it. */
data class KeyBroadcast(val action: String, val extraName: String, val keyValue: Int)

/**
 * ClimateControl — HVAC write path via the CAN app's own key receiver.
 *
 *   launcher --"CAR_AIR_KEY_KEY"{car_key_value}--> canbus2 CarAirClickWithVoice
 *            --> CanDataParseBase.btnClickEvent(key) --> CAN-box frame --> MCU --> car
 *
 * The receiver is registered without a permission by `CanDataParseBase` at construction
 * (`CB/model/CanDataParseBase.java:278-281`, `CB/CarAirClickWithVoice.java:427-436,458-466`).
 * Preferred over `ACTION_MCU_CMD_EVENT` raw frames because the CAN app already knows which
 * box is fitted.
 *
 * UNVERIFIED on-device: the car has not been driven with this; whether the RAV4 honours the
 * resulting CAN frames (STATUS goal #3) is still open. `CarAirClickWithVoice.clickBtn` also
 * drops presses while its `bControlBySpecial` flag is set.
 */
class ClimateControl(private val appContext: Context) {

    /** Fire one button press. Fire-and-forget; state comes back on the `carairstruct` broadcast. */
    fun press(button: ClimateButton) {
        val key = keyBroadcast(button)
        val intent = Intent(key.action).putExtra(key.extraName, key.keyValue)

        runCatching { appContext.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "climate ${button.name} failed", it) }
    }

    companion object {
        private const val TAG = "ClimateControl"

        /** `CarAirClickWithVoice.java:432` — matched with `startsWith`, exact name used. */
        const val ACTION_CAR_AIR_KEY = "CAR_AIR_KEY_KEY"

        /** `CarAirClickWithVoice.java:462`; the receiver defaults a missing extra to -1. */
        const val EXTRA_KEY_VALUE = "car_key_value"

        /** What [press] sends, without touching the framework. */
        fun keyBroadcast(button: ClimateButton): KeyBroadcast =
            KeyBroadcast(ACTION_CAR_AIR_KEY, EXTRA_KEY_VALUE, button.keyValue)
    }
}
