package com.reveng.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.notif.ShelfNotification
import com.reveng.carlauncher.ui.theme.carShape
import com.reveng.carlauncher.ui.theme.carCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.7 — the notification shelf (parked-only; gated by the caller, not here).
 *
 * Two panes on the 1920x720 panel: the notifications themselves get the reading width, the per-app
 * filter sits beside them rather than behind a settings trip. Muting an app is a thing you do
 * *because* of what you are looking at, so the control belongs next to it.
 *
 * Deliberately not a system shade clone. No icons, no actions, no reply — a row is app, title,
 * text, when, and one big dismiss target. Notification *actions* are PendingIntents that can do
 * anything, and firing arbitrary app intents from a launcher's shelf is not a thing to do while
 * someone is sitting in a car.
 */
@Composable
fun NotificationShelfScreen(
    items: List<ShelfNotification>,
    muted: Set<String>,
    listenerEnabled: Boolean,
    onSetMuted: (String, Boolean) -> Unit,
    onDismiss: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    onBack: () -> Unit,
) {
    val visible = items.filterNot { it.packageName in muted }

    // The filter list is built from everything captured, muted included — otherwise muting an app
    // would remove the only control that could un-mute it.
    val apps = items.distinctBy { it.packageName }
        .map { it.packageName to it.appLabel }
        .sortedBy { it.second.lowercase() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", onClick = onBack)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = "Notifications",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitleFor(visible.size, items.size, listenerEnabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(modifier = Modifier.weight(LIST_WEIGHT).fillMaxHeight()) {
                if (visible.isEmpty()) {
                    EmptyShelf(listenerEnabled = listenerEnabled, hasMutedAll = items.isNotEmpty())
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(visible, key = { it.key }) { item ->
                            NotificationRow(
                                item = item,
                                onOpen = { onOpenApp(item.packageName) },
                                onDismiss = { onDismiss(item.key) },
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(FILTER_WEIGHT)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Show on shelf",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                if (apps.isEmpty()) {
                    Text(
                        text = "Apps appear here once they have posted something.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                apps.forEach { (pkg, label) ->
                    AppFilterRow(
                        label = label,
                        shown = pkg !in muted,
                        onChange = { shown -> onSetMuted(pkg, !shown) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(item: ShelfNotification, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val open = withTapFeedback(onOpen)
    val dismiss = withTapFeedback(onDismiss)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .carCard()
            .clip(carShape(CARD_CORNER_DP.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = open)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.appLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = relativeTime(item.postedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.title.isNotEmpty()) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.text.isNotEmpty()) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Two lines is the glance budget. A long message is read stopped, in the app.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(DISMISS_TARGET_DP.dp)
                .clip(carShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = dismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun AppFilterRow(label: String, shown: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .carCard()
            .clip(carShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onChange(!shown) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = shown,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/**
 * The empty state has to distinguish three very different situations, because they look identical
 * otherwise: nothing has arrived, the listener was never enabled, or everything is muted.
 */
@Composable
private fun EmptyShelf(listenerEnabled: Boolean, hasMutedAll: Boolean) {
    val message = when {
        !listenerEnabled ->
            "Notification access is off. Enable \"Car Launcher\" under notification access, " +
                "or let the launcher grant it with root on the next start."
        hasMutedAll -> "Every app that has posted is muted on the right."
        else -> "Nothing new."
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

private fun subtitleFor(visible: Int, total: Int, listenerEnabled: Boolean): String {
    if (!listenerEnabled) {
        return "Notification access not granted"
    }
    if (total == visible) {
        return "$visible showing"
    }
    return "$visible showing · ${total - visible} muted"
}

/** "now" / "12 min" / "3 h" / a wall clock past a day — a driver wants recency, not a timestamp. */
private fun relativeTime(postedAtMs: Long): String {
    val age = System.currentTimeMillis() - postedAtMs
    return when {
        age < MINUTE_MS -> "now"
        age < HOUR_MS -> "${age / MINUTE_MS} min"
        age < DAY_MS -> "${age / HOUR_MS} h"
        else -> SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(postedAtMs))
    }
}

/** The list gets the reading width; the filter panel only holds app names and switches. */
private const val LIST_WEIGHT = 0.62f
private const val FILTER_WEIGHT = 0.38f

private const val CARD_CORNER_DP = 16
private const val DISMISS_TARGET_DP = 52

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
