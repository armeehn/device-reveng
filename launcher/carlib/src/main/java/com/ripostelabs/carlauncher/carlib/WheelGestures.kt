package com.ripostelabs.carlauncher.carlib

/**
 * The RAV4 steering-wheel keys as the CAN box reports them, and the two vendor codes each one
 * later turns into.
 *
 * Frame 0x11 carries `bArr[4]` = key id and `bArr[5]` = 1 while held / 0 released
 * (`HiworldCanParseToyota.java:831-891`, `OnHandleCanKeyCmd`). The id table is that method's
 * `iCanCar_button` → `g_byKeyVal` switch (`:853-885`); [mcuKey] is the `MCU_KEY_*` code it
 * hands to `sendMCUKey` (`:888`) and so the code the gateway's `ProcessCanKey` receives
 * (`EventService.java:13021-13110`). Two ids alias PREV (8, 13) and NEXT (9, 14); which physical
 * button sends which is UNVERIFIED — both are folded here so it cannot matter.
 *
 * VOLUME_UP (1) / VOLUME_DOWN (2) are deliberately absent: the CAN app owns their auto-repeat
 * (`:838-848`, one MCU key per frame after [WheelGestures.VOL_REPEAT_FRAMES] held frames), so a
 * hold there is already spoken for.
 */
enum class WheelKey(val canIds: Set<Int>, val mcuKey: Int) {
    PREV(setOf(8, 13), SwcFallback.MCU_KEY_PREV),
    NEXT(setOf(9, 14), SwcFallback.MCU_KEY_NEXT),
    MODE(setOf(12), SwcFallback.MCU_KEY_MODE),
    PLAY_PAUSE(setOf(15), SwcFallback.MCU_KEY_PLAYPAUSE),
    /** Id 5 is TALK while idle and HANGUP during a call (`:862-866`); we read it as TALK. */
    TALK(setOf(5), SwcFallback.MCU_KEY_TALK),
    HANGUP(setOf(6), SwcFallback.MCU_KEY_HANGUP),
    RETURN(setOf(16), SwcFallback.MCU_KEY_RETURN),
    MUTE(setOf(3), SwcFallback.MCU_KEY_MUTE),
    VOICE(setOf(4), SwcFallback.MCU_KEY_VOICE);

    companion object {
        private val byCanId = values().flatMap { k -> k.canIds.map { it to k } }.toMap()
        private val byMcuKey = values().associateBy { it.mcuKey }

        /** The key behind a frame-0x11 id, or null for volume / an id the table does not name. */
        fun fromCanId(id: Int): WheelKey? = byCanId[id]

        /** The key behind an `MCU_KEY_INFOR` / `ZXW_CAN_KEY_EVT` code, or null. */
        fun fromMcuKey(code: Int?): WheelKey? = byMcuKey[code]
    }
}

/**
 * What a run of frame-0x11 samples for one key amounted to. Exactly one of these per physical
 * press: a [LongPress] press never also emits [Press]; a [DoublePress] is the second press of
 * the pair, and that press emits nothing else.
 */
sealed interface WheelGesture {
    val key: WheelKey

    /** Released before [WheelGestures.LONG_PRESS_MS]. Informational: the vendor acts on it. */
    data class Press(override val key: WheelKey) : WheelGesture

    /** Held for [WheelGestures.LONG_PRESS_MS]; emitted while the key is still down. */
    data class LongPress(override val key: WheelKey) : WheelGesture

    /** A second press within [WheelGestures.DOUBLE_PRESS_MS] of the previous release. */
    data class DoublePress(override val key: WheelKey) : WheelGesture
}

/**
 * Hold and double-press detection over the raw CAN frames, because the vendor path throws the
 * duration away: the CAN app emits its MCU key once, on the release frame, whatever the hold
 * (`HiworldCanParseToyota.java:849-891`).
 *
 * ```
 *  0x11 frames    held held held held held held  rel
 *  t (ms)         0    100  200  300  400  500  600  700
 *                 │                             │
 *                 press start            LongPress (600 ms, still held)
 *                                                 └─ release: nothing more
 *
 *  0x11 frames    held rel        held rel
 *                 │    │          │
 *                 │    Press      DoublePress (second down within 400 ms of the release)
 * ```
 *
 * Pure: the caller feeds [onSample] per frame and [onTick] when [nextDeadlineMs] comes due, with
 * whatever clock it likes. No frame for [FRAME_GAP_MS] while a key is held is read as a release
 * (the CAN box may drop the release frame, or the bus may go quiet).
 *
 * UNVERIFIED on the car: the ~[FRAME_PERIOD_MS] repeat of 0x11 while a key is held is inferred
 * from the CAN app's per-frame repeat counter (`receive_can_key_time`), not measured. The three
 * thresholds are choices, not vendor facts.
 */
class WheelGestures(private val emit: (WheelGesture) -> Unit) {

    private var held: WheelKey? = null
    private var pressStartMs = 0L
    private var lastFrameMs = 0L
    private var longFired = false
    private var doubled = false

    /** The last short release, for pairing with the next press. */
    private var lastReleaseKey: WheelKey? = null
    private var lastReleaseMs = 0L

    /** One frame-0x11 sample: `bArr[4]` as [canId], `bArr[5] == 1` as [held]. */
    fun onSample(canId: Int, held: Boolean, nowMs: Long) {
        if (!held) {
            release(nowMs)
            return
        }

        // Volume and unknown ids are not ours; a held one still ends whatever we were tracking.
        val key = WheelKey.fromCanId(canId)
        if (key == null) {
            release(nowMs)
            return
        }

        // A different key without a release frame in between: end the old press first.
        if (this.held != null && this.held != key) {
            release(nowMs)
        }

        if (this.held == null) {
            begin(key, nowMs)
            return
        }

        lastFrameMs = nowMs
        fireLongIfDue(nowMs)
    }

    /** Time passed with no frame. Call at [nextDeadlineMs]; harmless any other time. */
    fun onTick(nowMs: Long) {
        if (held == null) {
            return
        }
        if (nowMs - lastFrameMs > FRAME_GAP_MS) {
            release(nowMs)
            return
        }
        fireLongIfDue(nowMs)
    }

    /** When [onTick] next has something to decide, or null while no key is held. */
    fun nextDeadlineMs(): Long? {
        if (held == null) {
            return null
        }
        val gap = lastFrameMs + FRAME_GAP_MS + 1
        if (longFired || doubled) {
            return gap
        }
        return minOf(gap, pressStartMs + LONG_PRESS_MS)
    }

    private fun begin(key: WheelKey, nowMs: Long) {
        held = key
        pressStartMs = nowMs
        lastFrameMs = nowMs
        longFired = false
        doubled = false

        // Second press of a pair: report it now, on the down edge, so it lands before the
        // vendor's own key for this press does.
        val pairs = lastReleaseKey == key && nowMs - lastReleaseMs <= DOUBLE_PRESS_MS
        lastReleaseKey = null
        if (pairs) {
            doubled = true
            emit(WheelGesture.DoublePress(key))
        }
    }

    private fun fireLongIfDue(nowMs: Long) {
        val key = held ?: return
        if (longFired || doubled || nowMs - pressStartMs < LONG_PRESS_MS) {
            return
        }
        longFired = true
        emit(WheelGesture.LongPress(key))
    }

    private fun release(nowMs: Long) {
        val key = held ?: return
        held = null

        // A long or double press has already been reported for this press.
        if (longFired || doubled) {
            return
        }
        emit(WheelGesture.Press(key))
        lastReleaseKey = key
        lastReleaseMs = nowMs
    }

    companion object {
        /** Hold this long for [WheelGesture.LongPress]; matches `KeyPump`'s threshold. */
        const val LONG_PRESS_MS = 600L

        /** Release-to-next-press gap that makes a [WheelGesture.DoublePress]. */
        const val DOUBLE_PRESS_MS = 400L

        /** No held frame for this long is read as a release. > 2 × [FRAME_PERIOD_MS]. */
        const val FRAME_GAP_MS = 300L

        /** Inferred 0x11 repeat while held. UNVERIFIED; only [FRAME_GAP_MS] depends on it. */
        const val FRAME_PERIOD_MS = 100L

        /** Held frames before the CAN app starts repeating VOL± (`receive_can_key_time > 5`). */
        const val VOL_REPEAT_FRAMES = 5
    }
}
