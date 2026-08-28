package com.reveng.carlauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.data.SettingsStore
import com.reveng.carlauncher.ui.theme.carCard
import com.reveng.carlauncher.ui.theme.carShape

/**
 * v2.5 — the launcher's own swipe-from-top shade, replacing the vendor/system pull-down.
 *
 * Wraps the whole Home surface: a thin grab strip pinned to the top edge opens a themed
 * [QuickControlsPanel] (volume / brightness / day-night / Wi-Fi / Bluetooth) that slides
 * down over a tap-to-dismiss scrim, and a drag-up on the panel closes it again. Everything
 * lives inside our own Compose window, so it always matches the active [CarTheme] — unlike
 * the stock Android quick-settings shade it stands in for.
 *
 * Purely additive: [content] is the existing Home tree, drawn untouched underneath. When
 * [enabled] is false the wrapper is transparent (no grab strip, no gesture) so the vendor
 * shade behaves exactly as before.
 */
@Composable
fun ShadeOverlay(
    carService: CarService,
    settingsStore: SettingsStore,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        content()

        if (enabled) {
            // Top-edge grab strip: a vertical drag downward past the threshold opens the shade.
            // Height is generous for a moving-vehicle tap target but stays clear of the status
            // row's own controls, which sit below it.
            var dragAccum = 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(28.dp)
                    .align(Alignment.TopCenter)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragAccum = 0f },
                            onDragEnd = { dragAccum = 0f },
                        ) { change, dragAmount ->
                            dragAccum += dragAmount
                            if (dragAccum > OPEN_THRESHOLD_PX) {
                                open = true
                                change.consume()
                            }
                        }
                    },
            )
        }

        // Scrim — fades in with the panel; a tap anywhere on it dismisses.
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { open = false },
            )
        }

        // The shade panel itself — slides down from the top edge.
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(tween(260)) { -it },
            exit = slideOutVertically(tween(220)) { -it },
        ) {
            var closeAccum = 0f
            Surface(
                shape = carShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp)
                    .carCard()
                    // Drag the panel up to close, mirroring the open gesture.
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { closeAccum = 0f },
                            onDragEnd = { closeAccum = 0f },
                        ) { change, dragAmount ->
                            closeAccum += dragAmount
                            if (closeAccum < -CLOSE_THRESHOLD_PX) {
                                open = false
                                change.consume()
                            }
                        }
                    },
            ) {
                Column {
                    QuickControlsPanel(
                        carService = carService,
                        settingsStore = settingsStore,
                        modifier = Modifier.padding(20.dp),
                    )
                    // A visible grab handle telegraphing the drag-up-to-close affordance.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 5.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    carShape(3.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

// Pixel thresholds — small enough to feel responsive, large enough to reject stray touches
// while driving. The gesture accumulates raw drag so a fast flick trips it in one move.
private const val OPEN_THRESHOLD_PX = 48f
private const val CLOSE_THRESHOLD_PX = 40f
