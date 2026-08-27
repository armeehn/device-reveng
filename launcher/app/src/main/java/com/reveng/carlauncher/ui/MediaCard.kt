package com.reveng.carlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.R
import com.reveng.carlauncher.media.NowPlaying
import kotlinx.coroutines.delay

/**
 * Media / now-playing card (CAR_API §6.3). Reads the active [NowPlaying] session (Spotify,
 * mpv, vendor player, …) via MediaSessionManager; transport buttons drive the owning app.
 * Falls back to a "no source connected" placeholder when nothing is playing.
 *
 * v0.9 (Media 2.0): a blurred album-art background, a live seek bar (position/duration from
 * PlaybackState, seek via MediaController.transportControls.seekTo → [onSeek]), elapsed/total
 * time, and a source chip naming the active app — tap to cycle when several apps are playing
 * ([onCycleSource]).
 */
@Composable
fun MediaCard(
    now: NowPlaying?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit = {},          // v0.9
    onCycleSource: () -> Unit = {},       // v0.9
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // v0.9: blurred album-art background (behind everything). Themeable scrim on top
            // keeps text legible in any theme. Blur needs API 31+ (minSdk is 33, so always on).
            val art = now?.art
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(28.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(0.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
                )
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // ---- top: art thumb + title/artist + source chip ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (art != null) {
                            Image(
                                bitmap = art.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(56.dp),
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = now?.title ?: stringResource(R.string.media_placeholder_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when {
                                now == null -> stringResource(R.string.media_placeholder_subtitle)
                                now.artist.isBlank() -> stringResource(R.string.media_playing)
                                else -> now.artist
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // ---- source chip (active app; tap to cycle when several sessions) ----
                if (now?.sourceLabel != null) {
                    SourceChip(
                        label = now.sourceLabel,
                        canCycle = now.sessionCount > 1,
                        onClick = onCycleSource,
                    )
                }

                // ---- seek bar (only when the session reports a real duration) ----
                if (now != null && now.durationMs > 0) {
                    SeekBar(now = now, onSeek = onSeek)
                }

                // ---- transport ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPrev, enabled = now?.hasPrev ?: false) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    FilledIconButton(onClick = onPlayPause) {
                        Icon(
                            if (now?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    IconButton(onClick = onNext, enabled = now?.hasNext ?: false) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Live seek bar. Ticks the interpolated position while playing; dragging scrubs locally and
 * commits on release via [onSeek] (MediaController.transportControls.seekTo). Disabled when
 * the session doesn't advertise ACTION_SEEK_TO (bar still reflects progress).
 */
@Composable
private fun SeekBar(now: NowPlaying, onSeek: (Long) -> Unit) {
    val duration = now.durationMs.coerceAtLeast(1L)
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var livePos by remember { mutableLongStateOf(now.livePositionMs()) }

    // Re-tick whenever the source snapshot changes (new position base / play state).
    LaunchedEffect(now.positionMs, now.positionTimestamp, now.isPlaying) {
        while (true) {
            if (!scrubbing) livePos = now.livePositionMs()
            if (!now.isPlaying) break
            delay(500)
        }
    }

    val displayMs = if (scrubbing) scrubValue.toLong() else livePos
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayMs.coerceIn(0L, duration).toFloat(),
            onValueChange = { scrubbing = true; scrubValue = it },
            onValueChangeFinished = { onSeek(scrubValue.toLong()); scrubbing = false },
            valueRange = 0f..duration.toFloat(),
            enabled = now.canSeek,
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(displayMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTime(now.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceChip(label: String, canCycle: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .then(if (canCycle) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canCycle) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = "Cycle source",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
