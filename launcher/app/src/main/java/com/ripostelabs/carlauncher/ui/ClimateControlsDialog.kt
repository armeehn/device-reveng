package com.ripostelabs.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.ClimateButton
import com.ripostelabs.carlauncher.carlib.ClimateState
import com.ripostelabs.carlauncher.ui.theme.carCard
import com.ripostelabs.carlauncher.ui.theme.carShape

/**
 * ClimateControlsDialog — the HVAC keys the stock climate screen had, one chip per button.
 *
 *   [ 21.5℃ ] − +   [ Fan 3/7 ] − +   [ 22.0℃ ] − +
 *   Power  A/C  AUTO  Recirc  Dual  Front def  Rear def  Seat L  Seat R
 *
 * Every chip is a stock key press through [CarService.pressClimate]; the lit state is what the
 * car reported on [CarEvents.climate], never what was tapped, so a key the car ignores shows as
 * ignored. UNVERIFIED on the car: the frames are the vendor's, the RAV4's answer is not yet seen.
 */
@Composable
fun ClimateControlsDialogHost(
    open: Boolean,
    onDismiss: () -> Unit,
    carService: CarService,
    carEvents: CarEvents,
) {
    if (!open) {
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = carShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(DIALOG_WIDTH_FRACTION)
                .padding(8.dp)
                .carCard(),
        ) {
            ClimateControlsPanel(
                carService = carService,
                carEvents = carEvents,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

@Composable
private fun ClimateControlsPanel(
    carService: CarService,
    carEvents: CarEvents,
    modifier: Modifier = Modifier,
) {
    val reported by carEvents.climate.collectAsStateSafe(initial = null)
    val state = reported?.takeIf { it.valid } ?: ClimateState()
    val press: (ClimateButton) -> Unit = { button -> runCatching { carService.pressClimate(button) } }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Climate",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )

        // Stepped values: setpoints and blower, each with its own down/up pair.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stepper(
                label = state.leftTempLabel(),
                caption = "Driver",
                modifier = Modifier.weight(1f),
                onDown = { press(ClimateButton.LEFT_TEMP_DOWN) },
                onUp = { press(ClimateButton.LEFT_TEMP_UP) },
            )
            Stepper(
                label = "${state.fanLevel}/${state.fanMax}",
                caption = "Fan",
                modifier = Modifier.weight(1f),
                onDown = { press(ClimateButton.FAN_DOWN) },
                onUp = { press(ClimateButton.FAN_UP) },
            )
            Stepper(
                label = state.rightTempLabel(),
                caption = "Passenger",
                modifier = Modifier.weight(1f),
                onDown = { press(ClimateButton.RIGHT_TEMP_DOWN) },
                onUp = { press(ClimateButton.RIGHT_TEMP_UP) },
            )
        }

        // Toggles: lit from the car's own report, not from the tap.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Power", state.powerOn, Modifier.weight(1f)) { press(ClimateButton.POWER) }
            ToggleChip("A/C", state.acOn, Modifier.weight(1f)) { press(ClimateButton.AC) }
            ToggleChip("AUTO", state.autoOn, Modifier.weight(1f)) { press(ClimateButton.AUTO) }
            ToggleChip("Recirc", !state.outsideAir && state.powerOn, Modifier.weight(1f)) { press(ClimateButton.RECIRCULATE) }
            ToggleChip("Dual", state.dualOn, Modifier.weight(1f)) { press(ClimateButton.DUAL) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Front def", state.frontDefrost, Modifier.weight(1f)) { press(ClimateButton.FRONT_DEFROST) }
            ToggleChip("Rear def", state.rearDefrost, Modifier.weight(1f)) { press(ClimateButton.REAR_DEFROST) }
            ToggleChip(seatLabel("Seat L", state.leftSeatHeat), state.leftSeatHeat > 0, Modifier.weight(1f)) {
                press(ClimateButton.LEFT_SEAT_HEAT)
            }
            ToggleChip(seatLabel("Seat R", state.rightSeatHeat), state.rightSeatHeat > 0, Modifier.weight(1f)) {
                press(ClimateButton.RIGHT_SEAT_HEAT)
            }
        }

        Text(
            text = "Sends the stock HVAC keys. Not yet verified on the car.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Seat L 2" while heating, plain "Seat L" when off. */
private fun seatLabel(base: String, level: Int): String = if (level > 0) "$base $level" else base

@Composable
private fun Stepper(
    label: String,
    caption: String,
    modifier: Modifier = Modifier,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(carShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StepButton(Icons.Filled.Remove, "$caption down", onDown)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AutoSizeText(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            AutoSizeText(
                text = caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StepButton(Icons.Filled.Add, "$caption up", onUp)
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: String,
    onClick: () -> Unit,
) {
    val tap = withTapFeedback(onClick)
    Icon(
        imageVector = icon,
        contentDescription = action,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .size(STEP_TARGET_DP.dp)
            .clip(carShape(12.dp))
            .clickable(onClick = tap)
            .padding(STEP_ICON_PAD_DP.dp),
    )
}

@Composable
private fun ToggleChip(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val tap = withTapFeedback(onClick)
    Row(
        modifier = modifier
            .clip(carShape(12.dp))
            .background(bg)
            .clickable(onClick = tap)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutoSizeText(text = label, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

/** Same width as Quick Controls, so the two dialogs sit identically on the 1920 px panel. */
private const val DIALOG_WIDTH_FRACTION = 0.6f

/** 48 dp tap box around a 24 dp glyph (tap-target audit, 2026-09-22). */
private const val STEP_TARGET_DP = 48
private const val STEP_ICON_PAD_DP = 12
