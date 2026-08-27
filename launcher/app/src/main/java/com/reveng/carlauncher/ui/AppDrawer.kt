package com.reveng.carlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.reveng.carlauncher.AppInfo

/**
 * App-drawer grid — every MAIN/LAUNCHER activity from [AppInfo], big tap targets for a
 * moving vehicle. Tapping launches the app (CAR_API §6.3, "App list").
 */
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(apps, key = { it.packageName + "/" + it.activityName }) { app ->
            AppTile(app = app, onClick = { onLaunch(app) })
        }
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
        val painter: Painter = rememberDrawablePainter(app)
        Image(
            painter = painter,
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

/**
 * Convert a launcher [android.graphics.drawable.Drawable] icon into a Compose Painter by
 * rasterizing to a bitmap. Kept simple (no Coil dependency); good enough for a fixed set
 * of launcher icons.
 */
@Composable
private fun rememberDrawablePainter(app: AppInfo): Painter {
    val bmp = androidx.compose.runtime.remember(app.packageName + app.activityName) {
        app.icon.toBitmap(width = 144, height = 144).asImageBitmap()
    }
    return BitmapPainter(bmp)
}
