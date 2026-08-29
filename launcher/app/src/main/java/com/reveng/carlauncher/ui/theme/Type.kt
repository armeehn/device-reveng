package com.reveng.carlauncher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.reveng.carlauncher.R

// Enlarged type scale — legible from a driver's distance on a 1920x720 panel.
//
// Every slot a screen actually reads is pinned here at car scale. The M3 phone defaults
// (titleMedium 16, bodyMedium 14, bodySmall 12, labelMedium 12, labelSmall 11) used to
// fall through onto driving surfaces; the small end of the scale is now clamped to the
// LAUNCHER_DESIGN §1.1 16 sp floor, deliberately collapsing hierarchy below it.
val CarTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 72.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
)

/** JetBrains Mono (OFL) in the three weights the Riposte brand allows: 400 / 700 / 800. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_extrabold, FontWeight.ExtraBold),
)

/**
 * The Riposte type scale (ripostelabs.xyz/brand SEC.03): one mono family, hierarchy by
 * size/weight/tracking. Sizes stay on the enlarged in-car scale of [CarTypography] —
 * glanceability wins over print fidelity — but weights snap to the brand's 400/700/800
 * and headings/labels take the brand tracking (.03em heads, .1em labels).
 */
val MonoTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 72.sp, letterSpacing = 0.03.em),
    headlineMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = 0.03.em),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 0.03.em),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.03.em),
    titleSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 0.03.em),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 18.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.1.em),
    labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.1.em),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.1.em),
).withFontFamily(JetBrainsMono)

/**
 * Stamp [ff] onto every slot (M3 has no defaultFontFamily), and snap the default Medium
 * (500) weights on title/label slots up to Bold — the brand family ships no 500 weight,
 * so leaving them would make Android synthesize a fake medium.
 */
private fun Typography.withFontFamily(ff: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = ff),
    displayMedium = displayMedium.copy(fontFamily = ff),
    displaySmall = displaySmall.copy(fontFamily = ff),
    headlineLarge = headlineLarge.copy(fontFamily = ff),
    headlineMedium = headlineMedium.copy(fontFamily = ff),
    headlineSmall = headlineSmall.copy(fontFamily = ff),
    titleLarge = titleLarge.copy(fontFamily = ff),
    titleMedium = titleMedium.copy(fontFamily = ff, fontWeight = FontWeight.Bold),
    titleSmall = titleSmall.copy(fontFamily = ff, fontWeight = FontWeight.Bold),
    bodyLarge = bodyLarge.copy(fontFamily = ff),
    bodyMedium = bodyMedium.copy(fontFamily = ff),
    bodySmall = bodySmall.copy(fontFamily = ff),
    labelLarge = labelLarge.copy(fontFamily = ff),
    labelMedium = labelMedium.copy(fontFamily = ff, fontWeight = FontWeight.Bold),
    labelSmall = labelSmall.copy(fontFamily = ff, fontWeight = FontWeight.Bold),
)
