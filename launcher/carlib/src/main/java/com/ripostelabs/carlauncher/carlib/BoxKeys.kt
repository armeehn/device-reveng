package com.ripostelabs.carlauncher.carlib

import kotlin.math.abs

/**
 * BoxKeys — the CAN box's own keys as [KeyAction]s: the car's panel buttons (0x21), its two
 * knobs (0x22), the voice button state (0xC5) and the car's mode key (0xE0). Stock routes all
 * four through `sendMCUKey` or `eventCenterPostRunActivity` (`HiworldCanParseToyota.java`).
 *
 * ```
 *  0x21 id 01 … id 00 ──▶ release ──▶ PANEL[id]           (:726-815)
 *  0x22 which pos     ──▶ pos - last, |d| < 10 ──▶ |d| steps (:676-724)
 *  0xC5 state         ──▶ TALK / HANGUP                   (:376-388)
 *  0xE0 mode          ──▶ RADIO / OPEN_MEDIA              (:390-408)
 * ```
 *
 * UNVERIFIED ON THE CAR: no capture has these opcodes yet. The wheel's NEXT and PREV proved
 * swapped against stock's constants (see [HiworldCanDecoder.swcAction]); the panel and the tune
 * knob follow stock until a drive says otherwise.
 */
class BoxKeys {

    private var panelHeld = 0
    private val knobLast = IntArray(KNOB_COUNT + 1)

    /** The actions one box frame produces; empty for any other signal. */
    fun onSignal(signal: CanSignal): List<KeyAction> {
        if (signal !is CanSignal.BoxKey) {
            return emptyList()
        }

        return when (signal.kind) {
            CanSignal.BoxKey.Kind.PANEL -> onPanel(signal.code, signal.value)
            CanSignal.BoxKey.Kind.KNOB -> onKnob(signal.code, signal.value)
            CanSignal.BoxKey.Kind.VOICE -> listOfNotNull(VOICE[signal.code])
            CanSignal.BoxKey.Kind.MODE -> listOfNotNull(MODE[signal.code])
        }
    }

    /** Press remembers the id; the release after it fires once (`:727-736`). */
    private fun onPanel(id: Int, state: Int): List<KeyAction> {
        if (state == PANEL_PRESSED) {
            panelHeld = id
            return emptyList()
        }
        if (state != PANEL_RELEASED || panelHeld == 0) {
            return emptyList()
        }

        val action = PANEL[panelHeld]
        panelHeld = 0
        return listOfNotNull(action)
    }

    /** A turn of under 10 detents is that many presses; any jump resyncs (`:680-697`). */
    private fun onKnob(which: Int, position: Int): List<KeyAction> {
        val keys = KNOB[which] ?: return emptyList()
        val delta = position - knobLast[which]
        knobLast[which] = position
        if (abs(delta) >= KNOB_MAX_STEP) {
            return emptyList()
        }

        val action = if (delta < 0) keys.second else keys.first
        return List(abs(delta)) { action }
    }

    private companion object {
        const val PANEL_PRESSED = 1
        const val PANEL_RELEASED = 0
        const val KNOB_COUNT = 2
        const val KNOB_MAX_STEP = 10

        /**
         * Panel id → action, via stock's `g_panel_byKeyVal` MCU key (`:738-802`). Ids stock
         * sends to power (1, 57), AUX (8), its own console (18, 97), BT phone (40, 48) or the
         * menu (47) have no launcher twin and are left out.
         */
        val PANEL: Map<Int, KeyAction> = mapOf(
            2 to KeyAction.PREV,          // 3 MCU_KEY_PREV
            3 to KeyAction.NEXT,          // 2 MCU_KEY_NEXT
            6 to KeyAction.BACK,          // 85 MCU_KEY_RETURN
            9 to KeyAction.MUTE,          // 17 MCU_KEY_MUTE
            16 to KeyAction.PLAY_PAUSE,   // 6 MCU_KEY_PLAYPAUSE
            32 to KeyAction.NAV,          // 55 MCU_KEY_NAV
            33 to KeyAction.NAV,          // 55
            36 to KeyAction.MODE,         // 16 MCU_KEY_MODE
            42 to KeyAction.PLAY_PAUSE,   // 6
            43 to KeyAction.HOME,         // 76 MCU_KEY_SYS_HOME
            44 to KeyAction.MODE,         // 16
            51 to KeyAction.RADIO,        // SRC_RADIO
            52 to KeyAction.TALK,         // 23 MCU_KEY_TALK
            53 to KeyAction.HANGUP,       // 22 MCU_KEY_HANGUP
            59 to KeyAction.MODE,         // 16
            69 to KeyAction.VOLUME_UP,    // 18 MCU_KEY_VOL_ADD
            70 to KeyAction.VOLUME_DOWN,  // 19 MCU_KEY_VOL_SUB
            75 to KeyAction.RADIO,        // 253, stock opens its radio app
        )

        /** Knob 1 is volume (18/19), knob 2 is tune (2 NEXT / 3 PREV): (turn up, turn down). */
        val KNOB: Map<Int, Pair<KeyAction, KeyAction>> = mapOf(
            1 to (KeyAction.VOLUME_UP to KeyAction.VOLUME_DOWN),
            2 to (KeyAction.NEXT to KeyAction.PREV),
        )

        /** 0xC5: 1 is `sendMCUKey(23)` TALK, 2 and 5 are `(22)` HANGUP. */
        val VOICE: Map<Int, KeyAction> = mapOf(
            1 to KeyAction.TALK,
            2 to KeyAction.HANGUP,
            5 to KeyAction.HANGUP,
        )

        /** 0xE0: FM and AM (32, 33) open the radio, USB and BT music (34, 35) the player; AUX (39) has no twin. */
        val MODE: Map<Int, KeyAction> = mapOf(
            32 to KeyAction.RADIO,
            33 to KeyAction.RADIO,
            34 to KeyAction.OPEN_MEDIA,
            35 to KeyAction.OPEN_MEDIA,
        )
    }
}
