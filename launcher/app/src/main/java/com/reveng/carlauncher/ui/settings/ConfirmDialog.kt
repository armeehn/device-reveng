package com.reveng.carlauncher.ui.settings

import com.reveng.carlauncher.ui.theme.carCard
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.reveng.carlauncher.ui.LocalParkedOnlyLock // v2.5
import com.reveng.carlauncher.ui.withTapFeedback // v2.5

/**
 * v2.0 — a themed confirm/cancel dialog for actions that need a second tap (reboot, factory
 * reset). Drawn from [com.reveng.carlauncher.ui.theme.CarTheme] surfaces like the rest of the suite;
 * the confirm button turns [MaterialTheme.colorScheme.error] when [destructive].
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    // v2.5 §1.4: a destructive action (reboot, factory reset) is parked-only. The dialog still
    // opens and still explains itself — only the confirm is withheld, so the driver sees why
    // rather than tapping a button that silently does nothing.
    val blocked = destructive && LocalParkedOnlyLock.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .carCard()
                .clip(carShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (blocked) {
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "Available when parked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.size(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DialogButton(
                    label = "Cancel",
                    onClick = onDismiss,
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
                DialogButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    filled = true,
                    destructive = destructive,
                    enabled = !blocked,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = when {
        !filled -> MaterialTheme.colorScheme.surfaceVariant
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    // v2.5: confirmations are exactly the taps §1.4 wants acknowledged eyes-free.
    val click = withTapFeedback(onClick)
    Row(
        modifier = modifier
            .clip(carShape(14.dp))
            .background(bg.copy(alpha = alpha))
            .clickable(enabled = enabled, onClick = click)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = fg.copy(alpha = alpha),
        )
    }
}

/** Material's standard disabled-content opacity. */
private const val DISABLED_ALPHA = 0.38f
