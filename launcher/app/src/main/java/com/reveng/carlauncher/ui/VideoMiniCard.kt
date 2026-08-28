package com.reveng.carlauncher.ui

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.R
import com.reveng.carlauncher.media.MiniScreenState
import com.reveng.carlauncher.media.NowPlaying
import com.reveng.carlauncher.ui.theme.carCard
import com.reveng.carlauncher.ui.theme.carShape
import kotlin.math.roundToInt

/**
 * v4.1 — the video mini screen's home card. Takes the media card's place in the glance column
 * while a video session is on screen: a 16:9 slot the freeform video window is positioned over
 * (see [com.reveng.carlauncher.media.MiniScreenController]), and a control strip with the
 * playing title plus expand/close.
 *
 * The slot itself renders only a black well with a status line — when the mini window is up it
 * is completely covered, and when the launch failed the well is exactly where the driver is
 * looking for an explanation.
 */
@Composable
fun VideoMiniCard(
    now: NowPlaying,
    state: MiniScreenState,
    onSlotPositioned: (Rect) -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        // Same accent slot as MediaCard — this IS the media card while video plays (SEC.01).
        modifier = modifier.carCard(accent = MaterialTheme.colorScheme.primary),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(carShape(8.dp))
                    // A video well is black in every theme; the window covers it when active.
                    .background(Color.Black)
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInWindow()
                        if (b.width > 0f && b.height > 0f) {
                            onSlotPositioned(
                                Rect(
                                    b.left.roundToInt(),
                                    b.top.roundToInt(),
                                    b.right.roundToInt(),
                                    b.bottom.roundToInt(),
                                ),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (state) {
                        is MiniScreenState.Failed -> state.reason
                        else -> stringResource(R.string.video_mini_opening)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = now.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onExpand) {
                    Icon(
                        Icons.Filled.Fullscreen,
                        contentDescription = "Expand video",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close mini screen",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}
