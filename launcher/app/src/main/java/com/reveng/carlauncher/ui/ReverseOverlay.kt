package com.reveng.carlauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.R

/**
 * Full-screen reverse-camera overlay (CAR_API §1.3, §6.3 "Reverse / radar").
 *
 * Toggled by [visible], which the caller drives from CarEvents.reverse
 * (ACTION_BACKCAR_START/END, or the MCU_MSG_BACKCAR_* fallback for a normal app).
 * Renders an opaque black screen so the driver's attention goes to the camera feed.
 *
 * TODO: embed the actual camera. Options on this device:
 *   * host the vendor reverse Activity `com.szchoiceway.view.BackCarActivity`
 *     (exported, CAR_API §7) instead of drawing our own overlay, or
 *   * add a SurfaceView here and bind the reverse video input (the input type lives in
 *     SysVar `Sys_backcar_Video_Type` / `Sys_6752_Backcar_Video_Type`, CAR_API §2.3),
 *     optionally overlaying radar distances from MCU_CAR_CAN_RADAR_INFO (byte[]) and the
 *     dynamic trajectory from ZXW_CAN_WHEEL_TRACK_EVT (steering angle).
 */
@Composable
fun ReverseOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // Placeholder until a SurfaceView / vendor camera feed is embedded (see KDoc).
            Text(
                text = stringResource(R.string.reverse_overlay_hint),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF445159),
                modifier = Modifier.padding(24.dp),
            )
            // AndroidView { SurfaceView(it).also { sv -> /* bind reverse video input */ } }
        }
    }
}
