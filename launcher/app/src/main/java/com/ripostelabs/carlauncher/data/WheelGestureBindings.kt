package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.WheelGesture
import com.ripostelabs.carlauncher.carlib.WheelKey

/**
 * What a wheel-key hold or double press does. Dispatched by
 * [com.ripostelabs.carlauncher.input.WheelGestureDispatcher]; persisted by name, so reordering
 * is safe and a name that no longer exists falls back to [NONE].
 */
enum class WheelGestureAction(val label: String) {
    NONE("Nothing"),
    SEEK_FORWARD_30S("Skip ahead 30 s"),
    SEEK_BACK_10S("Skip back 10 s"),
    NEXT_TRACK("Next track"),
    PREV_TRACK("Previous track"),
    PLAY_PAUSE("Play / pause"),
    OPEN_MEDIA("Open Media"),
    OPEN_RADIO("Open Radio"),
    OPEN_HOME("Go Home"),
    RADIO_SEEK_UP("Radio seek up"),
    RADIO_SEEK_DOWN("Radio seek down"),
    RADIO_NEXT_PRESET("Radio next preset"),
    CLAIM_RADIO("Switch car audio to radio"),
    RELEASE_SOURCE("Hand audio back to Android"),
    SIRI("Siri (CarPlay)"),
    NAV("Navigation"),
    MUTE_TOGGLE("Mute / unmute"),
    VOICE("Vendor voice assistant"),
}

/**
 * The per-key gesture map: one action for a hold, one for a double press, plus the master
 * switch. Immutable; [SettingsStore] rebuilds it from DataStore on every change.
 *
 * Defaults: holds carry the useful secondary actions; every double press is [WheelGestureAction.NONE].
 * That asymmetry is the collateral rule. The vendor reports a wheel key on its release, so by
 * the time a second press can be recognised the first press has already done its plain job
 * (skipped a track, switched source). A double-press action therefore always lands on top of
 * that. A hold has no such collateral on the launcher's own screens: its release key is
 * dropped ([com.ripostelabs.carlauncher.carlib.WheelKeySwallow]), except for the buttons whose
 * plain action happens inside the gateway (MODE source switch, MUTE, TALK, VOICE).
 */
data class WheelGestureBindings(
    val enabled: Boolean = true,
    val long: Map<WheelKey, WheelGestureAction> = DEFAULT_LONG,
    val double: Map<WheelKey, WheelGestureAction> = emptyMap(),
) {

    /** The bound action for [gesture]; a plain press is the vendor's, so always [NONE]. */
    fun actionFor(gesture: WheelGesture): WheelGestureAction = when (gesture) {
        is WheelGesture.LongPress -> longOf(gesture.key)
        is WheelGesture.DoublePress -> doubleOf(gesture.key)
        is WheelGesture.Press -> WheelGestureAction.NONE
    }

    fun longOf(key: WheelKey): WheelGestureAction = long[key] ?: WheelGestureAction.NONE
    fun doubleOf(key: WheelKey): WheelGestureAction = double[key] ?: WheelGestureAction.NONE

    companion object {
        val DEFAULT_LONG: Map<WheelKey, WheelGestureAction> = mapOf(
            WheelKey.NEXT to WheelGestureAction.SEEK_FORWARD_30S,
            WheelKey.PREV to WheelGestureAction.SEEK_BACK_10S,
            WheelKey.PLAY_PAUSE to WheelGestureAction.MUTE_TOGGLE,
            WheelKey.RETURN to WheelGestureAction.OPEN_HOME,
            // MODE and TALK are unbound on purpose: their plain action runs inside the gateway
            // (source switch, phone app) and cannot be swallowed, so a hold would do both.
        )

        /** The keys the settings screen offers, in wheel order. HANGUP is not bindable. */
        val BINDABLE: List<WheelKey> = listOf(
            WheelKey.NEXT, WheelKey.PREV, WheelKey.MODE, WheelKey.PLAY_PAUSE,
            WheelKey.TALK, WheelKey.RETURN, WheelKey.MUTE, WheelKey.VOICE,
        )

        private const val PAIR_SEP = ','
        private const val KEY_SEP = '='

        /** `NEXT=SEEK_FORWARD_30S,PREV=SEEK_BACK_10S`; NONE entries are left out. */
        fun encode(map: Map<WheelKey, WheelGestureAction>): String =
            map.entries
                .filter { it.value != WheelGestureAction.NONE }
                .joinToString(PAIR_SEP.toString()) { "${it.key.name}$KEY_SEP${it.value.name}" }

        /**
         * Inverse of [encode]. Null (never stored) yields [fallback]; a stored string is taken
         * as-is even when empty, so clearing every binding sticks. Unknown key or action names
         * are skipped, never guessed.
         */
        fun decode(raw: String?, fallback: Map<WheelKey, WheelGestureAction>): Map<WheelKey, WheelGestureAction> {
            if (raw == null) {
                return fallback
            }
            val out = LinkedHashMap<WheelKey, WheelGestureAction>()
            for (pair in raw.split(PAIR_SEP)) {
                val parts = pair.split(KEY_SEP)
                if (parts.size != 2) {
                    continue
                }
                val key = WheelKey.values().firstOrNull { it.name == parts[0].trim() } ?: continue
                val action = WheelGestureAction.values().firstOrNull { it.name == parts[1].trim() } ?: continue
                out[key] = action
            }
            return out
        }
    }
}
