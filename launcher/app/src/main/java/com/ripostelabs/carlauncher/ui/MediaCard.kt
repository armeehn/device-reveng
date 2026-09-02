package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.ui.theme.carCard
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
import com.ripostelabs.carlauncher.ui.theme.carShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie // v4.1
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripostelabs.carlauncher.R
import com.ripostelabs.carlauncher.media.NowPlaying
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
    // RAV4-52: with a single session, a tap on the chip opens the source app (CarPlay only).
    onOpenSource: (() -> Unit)? = null,
) {
    Card(
        // Riposte hard-edge chrome; accent rotation (SEC.01) starts pink on the media card.
        modifier = modifier.carCard(accent = MaterialTheme.colorScheme.primary),
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
                        .clip(carShape(0.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
                )
            }

            Column(
                // 16dp (not 20) so art row + chip + seek bar + the 88dp transport row all
                // fit the card's home slot.
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // ---- top: art thumb + title/artist + source chip ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(carShape(8.dp)),
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
                                // v4.1: video sessions get the film icon so the card says what
                                // kind of thing is playing before the title is read.
                                imageVector = if (now?.isVideo == true) Icons.Filled.Movie
                                else Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(56.dp),
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        AutoSizeText(
                            text = now?.title ?: stringResource(R.string.media_placeholder_title),
                            // §2.2: now-playing title ≥28sp.
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = TITLE_SP.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        AutoSizeText(
                            text = when {
                                now == null -> stringResource(R.string.media_placeholder_subtitle)
                                now.artist.isBlank() -> stringResource(R.string.media_playing)
                                else -> now.artist
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ---- source chip (active app; tap to cycle when several sessions) ----
                if (now?.sourceLabel != null) {
                    val canCycle = now.sessionCount > 1
                    SourceChip(
                        label = now.sourceLabel,
                        canCycle = canCycle,
                        onClick = if (canCycle) onCycleSource else onOpenSource,
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
                    // §2.2: each transport control is an 88 dp target — the one interactive
                    // row of an otherwise glance card, kept full-size despite the far column.
                    IconButton(
                        onClick = onPrev,
                        enabled = now?.hasPrev ?: false,
                        modifier = Modifier.size(TRANSPORT_TARGET_DP.dp),
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(TRANSPORT_ICON_DP.dp),
                        )
                    }
                    FilledIconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(TRANSPORT_TARGET_DP.dp),
                    ) {
                        Icon(
                            if (now?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(TRANSPORT_ICON_DP.dp),
                        )
                    }
                    IconButton(
                        onClick = onNext,
                        enabled = now?.hasNext ?: false,
                        modifier = Modifier.size(TRANSPORT_TARGET_DP.dp),
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(TRANSPORT_ICON_DP.dp),
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
 *
 * v0.4.7 — scrubbing is parked-only, mirroring MediaScreen's Progress: while moving the same
 * progress renders as a read-only bar under [LocalParkedOnlyLock].
 */
@Composable
private fun SeekBar(now: NowPlaying, onSeek: (Long) -> Unit) {
    val duration = now.durationMs.coerceAtLeast(1L)
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var livePos by remember { mutableLongStateOf(now.livePositionMs()) }
    // v0.4.7 — a committed seek holds the bar at its target until the controller pushes a fresh
    // snapshot, instead of snapping back to the stale pre-drag position.
    var seekHold by remember { mutableStateOf(false) }

    // Re-tick whenever the source snapshot changes (new position base / play state).
    LaunchedEffect(now.positionMs, now.positionTimestamp, now.isPlaying) {
        seekHold = false
        while (true) {
            if (!scrubbing && !seekHold) livePos = now.livePositionMs()
            if (!now.isPlaying) break
            delay(500)
        }
    }

    // While moving, drop any in-progress drag: the gate can close mid-gesture.
    val locked = LocalParkedOnlyLock.current
    LaunchedEffect(locked) {
        if (locked) scrubbing = false
    }

    val displayMs = if (scrubbing) scrubValue.toLong() else livePos
    Column(modifier = Modifier.fillMaxWidth()) {
        if (locked) {
            LinearProgressIndicator(
                progress = { displayMs.coerceIn(0L, duration).toFloat() / duration.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(carShape(5.dp)),
            )
        } else {
            Slider(
                value = displayMs.coerceIn(0L, duration).toFloat(),
                onValueChange = { scrubbing = true; scrubValue = it },
                onValueChangeFinished = {
                    livePos = scrubValue.toLong()
                    seekHold = true
                    onSeek(scrubValue.toLong())
                    scrubbing = false
                },
                valueRange = 0f..duration.toFloat(),
                enabled = now.canSeek,
                modifier = Modifier.fillMaxWidth().height(24.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AutoSizeText(
                text = formatTime(displayMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AutoSizeText(
                text = formatTime(now.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceChip(label: String, canCycle: Boolean, onClick: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = carShape(50),
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoSizeText(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
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

/** §2.2 MediaCard spec: 28 sp title, 88 dp transport targets (icons drawn at 40 dp). */
private const val TITLE_SP = 28
private const val TRANSPORT_TARGET_DP = 88
private const val TRANSPORT_ICON_DP = 40
