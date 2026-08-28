package com.reveng.claudecar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// Warm dark palette, terracotta accent — night-cabin friendly, always dark
// (the vendor uiMode flips day/night but glare beats white panels in a car).
private val Terracotta = Color(0xFFD97757)
private val Ink = Color(0xFF16120E)
private val Panel = Color(0xFF241E17)
private val PanelHigh = Color(0xFF322A20)
private val Bone = Color(0xFFEDE4D8)
private val BoneDim = Color(0xFFA89B8A)

val ColorUserBubble = PanelHigh
val ColorToolChip = Color(0xFF3A3227)
val ColorError = Color(0xFFE2574B)
val ColorOk = Color(0xFF7BAE6F)
val ColorDim = BoneDim

private val Scheme = darkColorScheme(
    primary = Terracotta,
    onPrimary = Ink,
    background = Ink,
    onBackground = Bone,
    surface = Panel,
    onSurface = Bone,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = BoneDim,
    error = ColorError,
)

// Head unit is 1920x720 @240dpi viewed at arm's length — everything one step
// larger than phone defaults.
private val CarTypography = Typography(
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(fontSize = 24.sp),
    labelLarge = TextStyle(fontSize = 18.sp),
)

@Composable
fun ClaudeCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = CarTypography, content = content)
}
