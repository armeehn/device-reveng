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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.data.WatchEntry
import com.reveng.carlauncher.ui.theme.carShape
import com.reveng.carlauncher.ui.theme.carCard

/**
 * v2.7 — the Jellyfin continue-watching shelf (parked-only; gated by the caller).
 *
 * The header says what this list actually is, on screen and not just in a KDoc: it is what played
 * **on this unit**, recovered from the Jellyfin app's MediaSession, not the server's Continue
 * Watching row. A shelf that quietly implied it had talked to the server would be worse than no
 * shelf, because the first missing episode would read as a bug rather than a boundary.
 *
 * Every row does the same thing: open Jellyfin. There is no per-item resume — that needs the
 * server item GUID, which needs credentials we do not have (see [com.reveng.carlauncher.media.
 * JellyfinApp]). The progress bar is still worth drawing: knowing you were 40 minutes into
 * something is most of what "continue watching" is for.
 */
@Composable
fun ContinueWatchingScreen(
    entries: List<WatchEntry>,
    jellyfinLabel: String?,
    onOpenJellyfin: () -> Unit,
    onForget: (WatchEntry) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", onClick = onBack)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Continue watching",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Played on this head unit — not the server's resume list",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (jellyfinLabel != null) {
                OpenAppButton(label = "Open $jellyfinLabel", onClick = onOpenJellyfin)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        ) {
            if (entries.isEmpty()) {
                EmptyShelf(jellyfinLabel = jellyfinLabel)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(entries, key = { it.packageName + "/" + it.title }) { entry ->
                        WatchRow(
                            entry = entry,
                            onOpen = onOpenJellyfin,
                            onForget = { onForget(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchRow(entry: WatchEntry, onOpen: () -> Unit, onForget: () -> Unit) {
    val open = withTapFeedback(onOpen)
    val forget = withTapFeedback(onForget)
    val progress = entry.progress()

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
        Box(
            modifier = Modifier
                .size(PLAY_TILE_DP.dp)
                .clip(carShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.subtitle.isNotEmpty()) {
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (progress != null) {
                ProgressBar(fraction = progress)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${clockOf(entry.positionMs)} of ${clockOf(entry.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // A session that published no duration is common (live streams, and players that
                // simply don't fill the field). Say so rather than drawing a bar at zero.
                Text(
                    text = "Position unknown — the player published no duration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(FORGET_TARGET_DP.dp)
                .clip(carShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = forget),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove from shelf",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A plain two-box bar. Material's LinearProgressIndicator animates; the motion budget says no. */
@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT_DP.dp)
            .clip(carShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(carShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun OpenAppButton(label: String, onClick: () -> Unit) {
    val press = withTapFeedback(onClick)
    Box(
        modifier = Modifier
            .clip(carShape(14.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = press)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyShelf(jellyfinLabel: String?) {
    val message = if (jellyfinLabel == null) {
        "No Jellyfin client is installed on this unit. Side-load org.jellyfin.mobile (or the " +
            "Android TV client) and it will appear in the drawer, on quick launch, and here."
    } else {
        "Nothing yet. Play something in $jellyfinLabel and it lands here — this shelf is built " +
            "from what this unit plays, so it cannot show what you started on another device."
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 80.dp),
        )
    }
}

/** m:ss / h:mm:ss, whichever the length calls for. */
private fun clockOf(ms: Long): String {
    if (ms < 0) {
        return "—"
    }
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    if (hours > 0) {
        return "%d:%02d:%02d".format(hours, minutes, seconds)
    }
    return "%d:%02d".format(minutes, seconds)
}

private const val CARD_CORNER_DP = 16
private const val PLAY_TILE_DP = 48
private const val FORGET_TARGET_DP = 52
private const val BAR_HEIGHT_DP = 6
