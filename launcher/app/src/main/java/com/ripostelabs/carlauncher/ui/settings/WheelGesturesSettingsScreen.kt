package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.SwapMode
import com.ripostelabs.carlauncher.carlib.WheelGesture
import com.ripostelabs.carlauncher.carlib.WheelGestures
import com.ripostelabs.carlauncher.carlib.WheelKey
import com.ripostelabs.carlauncher.carlib.WheelKeySwallow
import com.ripostelabs.carlauncher.data.SettingsStore
import com.ripostelabs.carlauncher.data.WheelGestureAction
import com.ripostelabs.carlauncher.data.WheelGestureBindings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wheel gestures: what a hold or double press of each steering-wheel key does. Sibling of
 * [SteeringWheelSettingsScreen], which shows the vendor's learned map; this one is the
 * launcher's own layer over the raw CAN frames (`WheelGestures`).
 *
 * The live line at the top is the on-car check: nothing here is verified against the car yet,
 * and a screen that shows the last decoded gesture is how that changes.
 */
@Composable
fun WheelGesturesSettingsScreen(
    settingsStore: SettingsStore,
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val settings by settingsStore.settings.collectAsStateWithLifecycle()
    val bindings = settings.wheelGestures
    var last by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        carEvents.wheelGestures.collect { g ->
            last = "${gestureLabel(g)} ${keyLabel(g.key)}  ·  ${clock.format(Date())}"
        }
    }

    val actions = WheelGestureAction.values().map { it to it.label }
    // A plain press bound to NONE is not "nothing": the key keeps its stock action.
    val pressActions = actions.map { (a, label) -> a to if (a == WheelGestureAction.NONE) STOCK_LABEL else label }

    SettingsScaffold(title = "Wheel gestures", onBack = onBack) {
        SettingsSection(title = "Last gesture") {
            Text(
                text = last ?: "Hold or double-press a wheel key. Needs the raw CAN broadcast; " +
                    "volume keys are not decoded.",
                style = MaterialTheme.typography.bodyLarge,
                color = if (last == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        // RAV4-192: stock's two swaps. They act on the wheel, the car's panel keys and its
        // knobs, so they sit outside the gestures switch.
        SettingsSection(title = "Swap keys") {
            ToggleSetting(
                label = "Swap previous and next",
                description = "Wheel, panel and tune knob skip the other way",
                checked = settings.keySwap.prevNext == SwapMode.SWAPPED,
                onChange = { settingsStore.setSwapPrevNext(swapModeOf(it)) },
            )
            ToggleSetting(
                label = "Swap volume up and down",
                description = "Wheel, panel and volume knob turn the other way",
                checked = settings.keySwap.volume == SwapMode.SWAPPED,
                onChange = { settingsStore.setSwapVolume(swapModeOf(it)) },
            )
        }

        SettingsSection {
            ToggleSetting(
                label = "Wheel gestures",
                description = "Press, hold and double-press actions decoded from the CAN frames",
                checked = bindings.enabled,
                onChange = settingsStore::setWheelGesturesEnabled,
            )
        }

        WheelGestureBindings.BINDABLE.forEach { key ->
            SettingsSection(title = keyLabel(key)) {
                PickerSetting(
                    label = "Press",
                    current = bindings.pressOf(key),
                    options = pressActions,
                    onSelect = { settingsStore.setWheelPress(key, it) },
                    enabled = bindings.enabled,
                )
                PickerSetting(
                    label = "Hold",
                    current = bindings.longOf(key),
                    options = actions,
                    onSelect = { settingsStore.setWheelLong(key, it) },
                    enabled = bindings.enabled,
                )
                PickerSetting(
                    label = "Double press",
                    description = "The plain press has already fired by the second press",
                    current = bindings.doubleOf(key),
                    options = actions,
                    onSelect = { settingsStore.setWheelDouble(key, it) },
                    enabled = bindings.enabled,
                )
            }
        }

        SettingsSection(title = "Timing") {
            InfoRow("Hold", "${WheelGestures.LONG_PRESS_MS} ms")
            InfoRow("Double press", "within ${WheelGestures.DOUBLE_PRESS_MS} ms")
            InfoRow("Frame gap read as release", "${WheelGestures.FRAME_GAP_MS} ms")
            InfoRow("Vendor key dropped after a hold", "for ${WheelKeySwallow.SWALLOW_WINDOW_MS} ms")
        }
    }
}

private const val STOCK_LABEL = "Stock action"

private fun gestureLabel(g: WheelGesture): String = when (g) {
    is WheelGesture.Press -> "Press"
    is WheelGesture.LongPress -> "Hold"
    is WheelGesture.DoublePress -> "Double"
}

private fun keyLabel(key: WheelKey): String = when (key) {
    WheelKey.PREV -> "Previous"
    WheelKey.NEXT -> "Next"
    WheelKey.MODE -> "Mode"
    WheelKey.PLAY_PAUSE -> "Play / pause"
    WheelKey.TALK -> "Talk"
    WheelKey.HANGUP -> "Hang up"
    WheelKey.RETURN -> "Return"
    WheelKey.MUTE -> "Mute"
    WheelKey.VOICE -> "Voice"
}

private fun swapModeOf(checked: Boolean): SwapMode = if (checked) SwapMode.SWAPPED else SwapMode.NORMAL
