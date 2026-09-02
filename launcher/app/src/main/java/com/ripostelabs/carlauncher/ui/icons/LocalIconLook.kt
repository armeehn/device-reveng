package com.ripostelabs.carlauncher.ui.icons

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The active theme's [IconLook], provided by CarLauncherTheme. The default is only reached
 * outside the theme (previews); it paints white on black so a missing provider is visible.
 */
internal val LocalIconLook = staticCompositionLocalOf {
    IconLook(
        glyph = 0xFFFFFFFF.toInt(),
        letter = 0xFFFFFFFF.toInt(),
        plate = 0xFF000000.toInt(),
        border = 0xFFFFFFFF.toInt(),
        cornerScale = 1f,
        hardEdge = false,
        monoType = false,
    )
}
