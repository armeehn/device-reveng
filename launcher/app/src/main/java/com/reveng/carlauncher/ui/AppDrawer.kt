package com.reveng.carlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.reveng.carlauncher.AppInfo

/**
 * App-drawer grid. Shows user apps as tiles, plus a single "System" folder tile that opens a
 * dialog with the vendor/engineering/system apps — so they stay reachable but off the main
 * home screen. Big tap targets for a moving vehicle (CAR_API §6.3, "App list").
 */
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    systemApps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 0, // v0.6: 0 = adaptive sizing; >0 = fixed column count (SettingsStore)
) {
    var showSystem by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        // v0.6: honour the app-grid density from Settings, else fall back to adaptive.
        columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(apps, key = { it.packageName + "/" + it.activityName }) { app ->
            AppTile(app = app, onClick = { onLaunch(app) })
        }
        if (systemApps.isNotEmpty()) {
            item(key = "__system_folder__") {
                SystemFolderTile(count = systemApps.size, onClick = { showSystem = true })
            }
        }
    }

    if (showSystem) {
        SystemFolderDialog(
            systemApps = systemApps,
            onLaunch = { onLaunch(it); showSystem = false },
            onDismiss = { showSystem = false },
        )
    }
}

@Composable
private fun AppTile(app: AppInfo, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = rememberDrawablePainter(app),
            contentDescription = app.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The "System" folder entry — a folder-styled tile showing an apps icon + count. */
@Composable
private fun SystemFolderTile(count: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Apps,
                contentDescription = "System apps",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = "System ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Dialog housing the system/vendor apps grid. */
@Composable
private fun SystemFolderDialog(
    systemApps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "System apps",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(systemApps, key = { it.packageName + "/" + it.activityName }) { app ->
                        AppTile(app = app, onClick = { onLaunch(app) })
                    }
                }
            }
        }
    }
}

/**
 * Convert a launcher [android.graphics.drawable.Drawable] icon into a Compose Painter by
 * rasterizing to a bitmap. Kept simple (no Coil dependency).
 */
@Composable
private fun rememberDrawablePainter(app: AppInfo): Painter {
    val bmp = remember(app.packageName + app.activityName) {
        app.icon.toBitmap(width = 144, height = 144).asImageBitmap()
    }
    return BitmapPainter(bmp)
}
