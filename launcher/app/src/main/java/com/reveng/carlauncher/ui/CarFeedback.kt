package com.reveng.carlauncher.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.reveng.carlauncher.carlib.CarService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v2.5 — eyes-free touch confirmation (LAUNCHER_DESIGN §1.4).
 *
 * A driver reaching for a control without looking needs to know the tap landed. Two channels
 * carry that, because neither is reliable alone: the panel's haptic actuator, and the car's own
 * audio path via `IEventService.beep()` (CAR_API §3.2, ordinal 7). Haptics can be switched off
 * system-wide and some panels have no actuator; a beep is inaudible in a loud cabin.
 *
 * One instance serves both the Compose tree (via [LocalCarFeedback]) and MainActivity's
 * steering-wheel key handler, which sits outside composition — SWC presses are the case where
 * eyes-free confirmation matters most, so it must not be Compose-only.
 *
 * @param beepEnabled read at tap time, not captured, so the vendor `Set_TouchBeep` preference
 *   takes effect the moment it changes. We follow that preference rather than adding a competing
 *   setting of our own: a driver who silenced the vendor UI's touch tone does not want ours.
 */
class CarFeedback(
    private val view: View,
    private val carService: CarService?,
    private val scope: CoroutineScope,
    private val beepEnabled: () -> Boolean,
) {

    /**
     * Confirm one accepted interaction. Safe from the main thread: the haptic is immediate, and
     * the beep is a binder round-trip to the vendor gateway so it goes to [Dispatchers.IO] — an
     * IPC stall on the main thread would jank the very tap it is confirming.
     */
    fun tap() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (!beepEnabled()) {
            return
        }
        val service = carService ?: return
        scope.launch(Dispatchers.IO) { runCatching { service.beep() } }
    }
}

/**
 * Nullable so previews and un-wired call sites compose without a car: null means "no feedback
 * available here", not a missing dependency to crash on.
 */
val LocalCarFeedback: ProvidableCompositionLocal<CarFeedback?> = compositionLocalOf { null }

/**
 * Wrap [onClick] so an accepted touch also beeps and buzzes. Use at call sites a driver may hit
 * without looking — transport controls, the search keyboard, confirmations.
 */
@Composable
fun withTapFeedback(onClick: () -> Unit): () -> Unit {
    val feedback = LocalCarFeedback.current
    return remember(feedback, onClick) {
        {
            feedback?.tap()
            onClick()
        }
    }
}
