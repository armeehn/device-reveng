package com.ripostelabs.carlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.ripostelabs.carlauncher.data.Phase
import com.ripostelabs.carlauncher.data.WallpaperStore

/**
 * RAV4-196 — the theme's wallpaper behind Home, under a scrim of the theme background.
 *
 * The scrim keeps text that sits straight on the background (the "Apps" title, grid labels)
 * readable on any photo; the cards have their own surfaces. Night uses a heavier scrim, so a
 * bright day photo borrowed for the night does not light the cabin.
 */
@Composable
fun HomeWallpaper(store: WallpaperStore?, themeId: String, night: Boolean) {
    if (store == null) {
        return
    }
    val version by store.version.collectAsStateSafe(initial = 0)
    val phase = if (night) Phase.NIGHT else Phase.DAY
    val image by produceState<ImageBitmap?>(initialValue = null, themeId, phase, version) {
        value = store.load(themeId, phase)?.asImageBitmap()
    }
    val shown = image ?: return

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            bitmap = shown,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val scrim = if (night) NIGHT_SCRIM else DAY_SCRIM
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = scrim)),
        )
    }
}

private const val DAY_SCRIM = 0.45f
private const val NIGHT_SCRIM = 0.65f
