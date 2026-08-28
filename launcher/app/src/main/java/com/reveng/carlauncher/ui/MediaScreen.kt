package com.reveng.carlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.reveng.carlauncher.input.focusRing // v2.8
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveng.carlauncher.media.MediaSource
import com.reveng.carlauncher.media.NowPlaying
import com.reveng.carlauncher.ui.theme.carShape
import kotlinx.coroutines.delay

/**
 * v2.6 — the full-screen media player (LAUNCHER_DESIGN §3.3).
 *
 * [MediaCard] is a glance surface in a 30%-wide Home column; this is the screen you land on
 * when you actually want to operate playback. It is laid out for the 1920x720 panel: large art
 * on the left, everything touchable on the right, in the driver's half.
 *
 * Two things follow the v2.5 motion gate:
 *
 *  * **Scrubbing is parked-only.** Dragging a seek bar to a target position is a sustained,
 *    precise, eyes-on gesture — the clearest §1.4 case in the whole app. While moving, the
 *    same progress is shown as a plain read-only bar, so the driver still sees where they are.
 *  * **Transport stays available.** Skip and play/pause are single, forgiving presses that
 *    exist on the steering wheel anyway; withholding them would push the driver to reach for
 *    their phone, which is worse.
 */
@Composable
fun MediaScreen(
    now: NowPlaying?,
    sources: List<MediaSource>,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectSource: (String) -> Unit,
    onBack: () -> Unit,
    vendorSource: String? = null,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Blurred art wash, as on the card, so the screen reads as the same surface enlarged.
        val art = now?.art
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(48.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = ART_SCRIM_ALPHA)),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            MediaHeader(vendorSource = vendorSource, onBack = onBack)

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                AlbumArt(
                    now = now,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TrackTitle(now = now)

                    if (sources.size > 1) {
                        SourcePicker(
                            sources = sources,
                            activePackage = now?.sourcePackage,
                            onSelect = onSelectSource,
                        )
                    }

                    if (now != null && now.durationMs > 0) {
                        Progress(now = now, onSeek = onSeek)
                    }

                    Transport(now = now, onPlayPause = onPlayPause, onNext = onNext, onPrev = onPrev)
                }
            }
        }
    }
}

@Composable
private fun MediaHeader(vendorSource: String?, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .size(48.dp)
                .clip(carShape(12.dp))
                .focusRing()
                .clickable(onClick = withTapFeedback(onBack)) // v2.8 ring
                .padding(8.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = "Media",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        // The vendor's own source (Bluetooth / USB / built-in). Read-only: see
        // CarService.getValidModeTitle for why we don't offer to switch it.
        if (!vendorSource.isNullOrBlank()) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "Car source: $vendorSource",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlbumArt(now: NowPlaying?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(carShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val art = now?.art
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
                modifier = Modifier.size(120.dp),
            )
        }
    }
}

/**
 * §3.3 asks for a 40 sp title. That is set explicitly rather than borrowed from a type ramp
 * step, because the requirement is a physical legibility one — it must stay 40 sp even if the
 * theme's headline sizes change.
 */
@Composable
private fun TrackTitle(now: NowPlaying?) {
    Column {
        Text(
            text = now?.title ?: "Nothing playing",
            fontSize = TITLE_SP.sp,
            lineHeight = (TITLE_SP * 1.15f).sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = now?.artist?.takeIf { it.isNotBlank() } ?: "Start playback in any app",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Every app currently holding a session, so the driver picks rather than cycles blindly. */
@Composable
private fun SourcePicker(
    sources: List<MediaSource>,
    activePackage: String?,
    onSelect: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(sources, key = { it.packageName }) { source ->
            val active = source.packageName == activePackage
            val bg = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            val fg = if (active) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = if (source.isPlaying) "▶ ${source.label}" else source.label,
                style = MaterialTheme.typography.titleMedium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(carShape(14.dp))
                    .background(bg)
                    .focusRing()
                    .clickable(onClick = withTapFeedback { onSelect(source.packageName) })
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Position and duration. Interactive while parked, read-only while moving — the v2.5 gate.
 *
 * Both variants tick from the same interpolated position, so the bar does not stall or jump
 * when the car starts or stops moving; only the ability to grab it changes.
 */
@Composable
private fun Progress(now: NowPlaying, onSeek: (Long) -> Unit) {
    val duration = now.durationMs.coerceAtLeast(1L)
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var livePos by remember { mutableLongStateOf(now.livePositionMs()) }

    LaunchedEffect(now.positionMs, now.positionTimestamp, now.isPlaying) {
        while (true) {
            if (!scrubbing) livePos = now.livePositionMs()
            if (!now.isPlaying) break
            delay(POSITION_TICK_MS)
        }
    }

    // While moving, drop any in-progress drag: the gate can close mid-gesture, and leaving
    // `scrubbing` true would freeze the bar at wherever the finger happened to be.
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
                onValueChangeFinished = { onSeek(scrubValue.toLong()); scrubbing = false },
                valueRange = 0f..duration.toFloat(),
                enabled = now.canSeek,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(displayMs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(now.durationMs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** §3.3's 96 dp transport targets — sized to be hit without aiming. */
@Composable
private fun Transport(
    now: NowPlaying?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            icon = Icons.Filled.SkipPrevious,
            description = "Previous",
            enabled = now?.hasPrev ?: false,
            onClick = onPrev,
        )
        TransportButton(
            icon = if (now?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            description = "Play or pause",
            enabled = now != null,
            filled = true,
            onClick = onPlayPause,
        )
        TransportButton(
            icon = Icons.Filled.SkipNext,
            description = "Next",
            enabled = now?.hasNext ?: false,
            onClick = onNext,
        )
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    filled: Boolean = false,
) {
    val bg = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(TRANSPORT_TARGET_DP.dp)
            .clip(carShape(24.dp))
            .background(bg.copy(alpha = alpha))
            .focusRing()
            .clickable(enabled = enabled, onClick = withTapFeedback(onClick)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = fg.copy(alpha = alpha),
            modifier = Modifier.size(48.dp),
        )
    }
}

/** mm:ss, or h:mm:ss once a track runs past an hour (audiobooks, podcasts, DJ sets). */
private fun formatDuration(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** LAUNCHER_DESIGN §3.3: 40 sp title, 96 dp transport targets. */
private const val TITLE_SP = 40f
private const val TRANSPORT_TARGET_DP = 96

private const val ART_SCRIM_ALPHA = 0.82f
private const val DISABLED_ALPHA = 0.38f

/** Twice a second is enough for a seek bar and costs nothing; matches MediaCard. */
private const val POSITION_TICK_MS = 500L
