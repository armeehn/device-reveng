package com.ripostelabs.carlauncher.carlib

/**
 * What a key does on Riposte OS 0.2, whatever carried it. The launcher dispatches these; the
 * vendor's equivalents are named in [KeyActions].
 */
enum class KeyAction {
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    NEXT,
    PREV,
    PLAY_PAUSE,
    /** The vendor's `switchMode` source cycle. */
    MODE,
    TALK,
    HANGUP,
    VOICE,
    HOME,
    BACK,
    RADIO,
    NAV,
    OPEN_MEDIA,
    SETTINGS,
}

/**
 * The key → action tables, transcribed from eventcenter. Three carriers, one vocabulary:
 *
 * ```
 *  CAN box 0x11 id ──▶ canbus2 g_byKeyVal ──▶ ProcessCanKey      (HiworldCanParseToyota.java:853-885,
 *                                                                  EventService.java:13021-13110)
 *  72 code          ──▶ onCmdKeyEvent                              (EventService.java:2401-2699)
 *  72 141+slot / 74 slot ──▶ WheelCustomKey name ──▶ setMcuKeyEvent (McuToArmDataManage.java:431-438,
 *                                                                  :616-1090)
 * ```
 *
 * Panel VOL+/VOL-/MUTE are absent on purpose: the owner already echoes them to the MCU as
 * `08 xx` ([McuOwner] onPanelKey), which is what `sendSystemKey` did. So are the fixed codes
 * with a `CAR_KEY` twin (NEXT, PREV, MENU, RETURN, MODE, TALK, RADIO): those ride
 * [SwcFallback.mcuKey] into the launcher's key pump, long press and all, and a second path
 * would fire them twice.
 */
object KeyActions {
    /** `MCU_KEY_NAV` (EventUtils.java:1538): `postRunModeActivity(SRC_GPS)`, EventService.java:2423. */
    const val PANEL_NAV = 0x37

    /** `MCU_KEY_WHEEL_INDEX1..15` (EventUtils.java:1633-1647) are learned slots 0..14. */
    private const val LEARNED_CODE_FIRST = 141
    private const val LEARNED_CODE_LAST = LEARNED_CODE_FIRST + WheelKeyMap.SLOT_MAX

    /** The plain press of a CAN wheel key (`ProcessCanKey`). Volume is not a [WheelKey]; see [KeyRouter]. */
    fun forCan(key: WheelKey): KeyAction = when (key) {
        WheelKey.PREV -> KeyAction.PREV
        WheelKey.NEXT -> KeyAction.NEXT
        WheelKey.MODE -> KeyAction.MODE
        WheelKey.PLAY_PAUSE -> KeyAction.PLAY_PAUSE
        WheelKey.TALK -> KeyAction.TALK
        WheelKey.HANGUP -> KeyAction.HANGUP
        WheelKey.RETURN -> KeyAction.BACK
        WheelKey.MUTE -> KeyAction.MUTE
        WheelKey.VOICE -> KeyAction.VOICE
    }

    /**
     * A fixed `72` code (`onCmdKeyEvent`) that no other launcher path acts on; null for the
     * rest (the `CAR_KEY` twins, the owner's volume echo, and codes the launcher ignores).
     */
    fun forPanel(code: Int): KeyAction? = when (code) {
        McuOwnerProtocol.Key.PLAY_PAUSE -> KeyAction.PLAY_PAUSE  // ProcessCanKey(6) → 85, :13084
        McuOwnerProtocol.Key.HANGUP -> KeyAction.HANGUP          // :2553-2556
        McuOwnerProtocol.Key.VOICE -> KeyAction.VOICE            // startVoice, :2630
        McuOwnerProtocol.Key.SETUP -> KeyAction.SETTINGS         // SRC_SETUP, :2549
        PANEL_NAV -> KeyAction.NAV                               // SRC_GPS, :2423
        else -> null
    }

    /** The slot behind a learned-wheel `72` code, or null for every other code. */
    fun learnedSlotOf(code: Int): Int? {
        if (code !in LEARNED_CODE_FIRST..LEARNED_CODE_LAST) {
            return null
        }
        return code - LEARNED_CODE_FIRST
    }

    /** A learned function (`setMcuKeyEvent`, cases by name); null where the launcher has no equivalent. */
    fun forFunction(function: WheelFunction): KeyAction? = when (function) {
        WheelFunction.HANG_UP -> KeyAction.HANGUP        // case 0 → ProcessCanKey(22)
        WheelFunction.VOLUME_UP -> KeyAction.VOLUME_UP   // case 3 → 18
        WheelFunction.VOLUME_DOWN -> KeyAction.VOLUME_DOWN // case 4 → 19
        WheelFunction.FM -> KeyAction.RADIO              // case 17 → 208
        WheelFunction.OK -> KeyAction.PLAY_PAUSE         // case 18 → 6
        WheelFunction.BACK -> KeyAction.BACK             // case 22 → androidReturn
        WheelFunction.HOME -> KeyAction.HOME             // case 23 → androidHome
        WheelFunction.MODE -> KeyAction.MODE             // case 26 → 16
        WheelFunction.MUTE -> KeyAction.MUTE             // case 27 → 17
        WheelFunction.NAVI -> KeyAction.NAV              // case 28 → 55
        WheelFunction.NEXT -> KeyAction.NEXT             // case 29 → 2
        WheelFunction.PREV -> KeyAction.PREV             // case 30 → 3
        WheelFunction.TALK -> KeyAction.TALK             // case 31 → 23
        WheelFunction.MUSIC -> KeyAction.OPEN_MEDIA      // case 34 → 242 SRC_MUSIC
        WheelFunction.SETTINGS -> KeyAction.SETTINGS     // case 36 → 20
        WheelFunction.VOICE -> KeyAction.VOICE           // case 38 → startVoice
        // POWER (case 35) is the standby toggle, the MCU's own power path; cameras, DSP,
        // loudness, split screen, recents and the car-console keys have no launcher twin.
        else -> null
    }
}

/**
 * One [McuOwner.Listener] that turns every key carrier on the owner path into [KeyAction]s.
 *
 * The CAN box's volume ids never reach [WheelGestures] (it excludes them by design), so the
 * canbus2 repeat rule lives here (`HiworldCanParseToyota.java:831-856`): every held frame
 * counts; from the 6th on, each frame is one step; a release after fewer than 6 is the one
 * and only step. Other CAN ids are the gesture engine's, fed back through [onCanPress].
 *
 * ```
 *  held frames   1 2 3 4 5 6 7 8   rel        1 2 rel
 *  VOL step                ▲ ▲ ▲   (none)         ▲
 * ```
 *
 * A learned resistive key acts on its down edge only (`74` state != 0, or `72 141+slot` with
 * status 1). UNVERIFIED which edges the MCU sends for a `72` custom key: the vendor acts on
 * every frame except where it checks `keyStatus == 1` (McuToArmDataManage.java:872, :1044).
 */
class KeyRouter(
    private val map: () -> WheelKeyMap,
    private val emit: (KeyAction) -> Unit,
) : McuOwner.Listener {

    private var heldFrames = 0
    private var heldId = 0

    override fun onPanelKey(key: McuOwnerProtocol.PanelKey) {
        val slot = KeyActions.learnedSlotOf(key.code)
        if (slot == null) {
            KeyActions.forPanel(key.code)?.let(emit)
            return
        }
        if (key.status != PRESSED) {
            return
        }
        emitSlot(slot)
    }

    override fun onWheelKey(key: McuOwnerProtocol.WheelKey) {
        if (!key.down) {
            return
        }
        emitSlot(key.slot)
    }

    override fun onCanSignal(signal: CanSignal, atMs: Long) {
        if (signal !is CanSignal.BasicStatus) {
            return
        }
        onVolumeSample(signal.swcButtonId, signal.swcPressed)
    }

    /** A plain CAN wheel press, as [WheelGestures] reports it on release. */
    fun onCanPress(key: WheelKey) {
        emit(KeyActions.forCan(key))
    }

    private fun emitSlot(slot: Int) {
        val function = map().functionOf(slot) ?: return
        KeyActions.forFunction(function)?.let(emit)
    }

    private fun onVolumeSample(id: Int, held: Boolean) {
        if (held) {
            heldFrames++
            if (id != 0) {
                heldId = id
            }
            if (heldFrames > VOL_REPEAT_AFTER_FRAMES) {
                volumeAction(heldId)?.let { emit(it); heldId = 0 }
            }
            return
        }

        // Release: one step for a short hold, nothing after a repeat already ran.
        heldFrames = 0
        val id = heldId
        heldId = 0
        volumeAction(id)?.let(emit)
    }

    private fun volumeAction(id: Int): KeyAction? = when (id) {
        CAN_VOLUME_UP -> KeyAction.VOLUME_UP
        CAN_VOLUME_DOWN -> KeyAction.VOLUME_DOWN
        else -> null
    }

    companion object {
        /** Frame-0x11 ids the CAN app turns into MCU_KEY_VOL_ADD / VOL_SUB (`:840-846`). */
        const val CAN_VOLUME_UP = 1
        const val CAN_VOLUME_DOWN = 2

        /** `receive_can_key_time > 5` (`:839`). */
        private const val VOL_REPEAT_AFTER_FRAMES = WheelGestures.VOL_REPEAT_FRAMES

        /** `72` byte 2 for the press of a custom key (`setMcuKeyEvent` checks `keyStatus == 1`). */
        private const val PRESSED = 1
    }
}
