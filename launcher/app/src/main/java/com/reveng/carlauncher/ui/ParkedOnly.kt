package com.reveng.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.ui.theme.carShape

/**
 * v2.5 — the parked-only safety gate (LAUNCHER_DESIGN §1.4).
 *
 * Distraction rules were unenforceable through v2.4.1 because `CarEvents.speedKmh` was a stub;
 * [com.reveng.carlauncher.carlib.GpsSpeedSource] now feeds it, so the features the design doc
 * marks as parked-only can actually be withheld while the car is in motion.
 *
 * The verdict travels as a [CompositionLocal] rather than a parameter. Gated features sit deep
 * in unrelated trees — the search keyboard, the theme editor, the SysVar browser, destructive
 * confirmations — and threading a boolean through every intermediate screen would touch a lot of
 * code that has nothing to do with motion. This mirrors how [com.reveng.carlauncher.input.
 * LocalLauncherFocus] already reaches the same screens.
 *
 * Default `false` keeps every @Preview and any un-wired caller usable.
 */
val LocalParkedOnlyLock: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** Provide the current lock verdict to everything in [content]. */
@Composable
fun ProvideParkedOnlyLock(locked: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalParkedOnlyLock provides locked, content = content)
}

/**
 * Render [content] when parked, or a large "Available when parked" panel while moving.
 *
 * [feature] names what is withheld ("Search", "Theme editor") so the panel says *which* thing is
 * unavailable rather than leaving the driver guessing which tap failed.
 */
@Composable
fun ParkedOnly(
    feature: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (!LocalParkedOnlyLock.current) {
        content()
        return
    }
    ParkedOnlyNotice(feature = feature, modifier = modifier, onBack = onBack)
}

/**
 * The withheld-feature panel. Sized to be read at a glance from the driver's seat: headline type,
 * high contrast, centred, and completely static — a moving car is exactly when an animation must
 * not be competing for attention (see the motion budget in launcher/README.md).
 *
 * [onBack] matters when this replaces a whole screen: the screen it hid owned the only Cancel
 * button, so without an exit here the driver would be stranded until they found a hardware key.
 */
@Composable
fun ParkedOnlyNotice(
    feature: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Filled.DirectionsCar,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = "Available when parked",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "$feature is hidden while the car is moving.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onBack != null) {
            Text(
                text = "Back",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(carShape(BACK_CORNER_DP.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 40.dp, vertical = 14.dp),
            )
        }
    }
}

/** Matches the confirm-dialog button radius so the exit reads as the suite's own control. */
private const val BACK_CORNER_DP = 14
