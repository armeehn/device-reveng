package com.ripostelabs.carlauncher.input

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v2.8 — what one physical key press turned into.
 *
 * [Press] with `repeat = true` is an auto-repeat tick rather than a fresh press. Consumers act on
 * both, but only confirm the first: buzzing and beeping thirty times while a driver holds the
 * tuner rocker is worse than silence (see MainActivity's feedback call).
 */
sealed interface NavEvent {
    data class Press(val key: NavKey, val repeat: Boolean = false) : NavEvent
    data class LongPress(val key: NavKey) : NavEvent
}

/**
 * v2.8 — press timing for both input sources.
 *
 * The two sources disagree about held keys and neither is usable raw. The vendor
 * `STEER_WHEEL_INFOR` broadcast (CAR_API §4) sends one down and one up and nothing in between, so
 * a held key moved the ring exactly once. Real Android [android.view.KeyEvent]s auto-repeat at the
 * *system's* rate, which is tuned for a phone keyboard and machine-guns a 12-item settings list
 * past the driver. Feeding both through one pump gives the launcher a single, tuned cadence.
 *
 * Two behaviours, split by key, because they cannot coexist on the same key: a key that repeats
 * can never also long-press (the repeat has already fired by 600 ms), and a key that long-presses
 * must defer its short action to the release.
 *  - [REPEATING] — directional keys. Fire immediately, then repeat after [REPEAT_DELAY_MS] every
 *    [REPEAT_INTERVAL_MS]. No long-press.
 *  - [LONG_PRESSABLE] — CENTER and BACK. The short action waits for the release; passing
 *    [LONG_PRESS_MS] fires the secondary action instead and swallows the release.
 * Anything else (media transport, the source keys) fires once on the way down.
 *
 * Not thread-safe: [down] / [up] are called from the main thread only (the broadcast receiver and
 * `dispatchKeyEvent` both run there), so a lock would buy nothing.
 */
class KeyPump(
    private val scope: CoroutineScope,
    private val emit: (NavEvent) -> Unit,
) {

    private var held: NavKey? = null
    private var timer: Job? = null
    private var longFired = false

    /** Begin a press. A repeated down for the key already held is ignored — our pacing owns it. */
    fun down(key: NavKey) {
        if (held == key) {
            return
        }
        cancel()
        held = key

        if (key in REPEATING) {
            emit(NavEvent.Press(key))
            timer = scope.launch {
                delay(REPEAT_DELAY_MS)
                while (isActive) {
                    emit(NavEvent.Press(key, repeat = true))
                    delay(REPEAT_INTERVAL_MS)
                }
            }
            return
        }

        if (key !in LONG_PRESSABLE) {
            emit(NavEvent.Press(key))
            return
        }

        timer = scope.launch {
            delay(LONG_PRESS_MS)
            longFired = true
            emit(NavEvent.LongPress(key))
        }
    }

    /** End a press. Emits the short [NavEvent.Press] only if the long-press did not already fire. */
    fun up(key: NavKey) {
        if (held != key) {
            return
        }
        val deferred = key in LONG_PRESSABLE && !longFired
        cancel()
        if (deferred) {
            emit(NavEvent.Press(key))
        }
    }

    /** Drop the held key and its timer — on unfocus, on destroy, or before a new press. */
    fun cancel() {
        timer?.cancel()
        timer = null
        held = null
        longFired = false
    }

    private companion object {
        /**
         * Hold-before-repeat. Long enough that a deliberate single press never double-fires while
         * a thumb rests on the wheel, short enough that holding to scroll feels intentional.
         */
        const val REPEAT_DELAY_MS = 400L

        /** ~7 steps/second: fast enough to cross a settings screen, slow enough to stop on target. */
        const val REPEAT_INTERVAL_MS = 150L

        /** The roadmap's threshold for a secondary action. */
        const val LONG_PRESS_MS = 600L

        val REPEATING = setOf(NavKey.UP, NavKey.DOWN, NavKey.LEFT, NavKey.RIGHT)
        val LONG_PRESSABLE = setOf(NavKey.CENTER, NavKey.BACK)
    }
}
