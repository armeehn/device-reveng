package com.ripostelabs.carlauncher.input

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.ui.theme.carShape

/**
 * v2.8 — the visible half of [KeyBridge]'s roving ring.
 *
 * [KeyBridge] moves Compose's focus; this draws where it landed. Material's own focus indication
 * is a low-contrast ripple overlay tuned for a desk monitor — at arm's length in daylight, on a
 * 1920x720 panel, it is invisible. This paints the same 3 dp primary border [launcherFocusTarget]
 * uses on Home, so one ring reads the same everywhere.
 *
 * Apply it *before* `clickable` in the chain: [onFocusChanged] reports for the focus target that
 * follows it, and `clickable` is what makes the node focusable.
 *
 * Deliberately not animated. [launcherFocusTarget] scales its target slightly, which suits eight
 * large Home regions; on a settings list it would make every row twitch as the ring passes, and
 * the motion budget in launcher/README.md exists to stop exactly that.
 */
@Composable
fun Modifier.focusRing(cornerRadiusDp: Int = RING_CORNER_DP): Modifier {
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.primary
    val shape = carShape(cornerRadiusDp.dp)

    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(RING_WIDTH_DP.dp, ring, shape) else Modifier)
}

/** Matches the settings kit's row/card radius so the ring traces the surface it highlights. */
private const val RING_CORNER_DP = 14

/** Same weight as the Home ring ([launcherFocusTarget]). */
private const val RING_WIDTH_DP = 3
