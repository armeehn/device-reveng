package com.ripostelabs.carlauncher.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * A [Text] that shrinks its font to fit the width it was given before it resorts to an
 * ellipsis. Drop-in replacement for the launcher's `maxLines = 1` + [TextOverflow.Ellipsis]
 * labels, which were sized for the sans type scale and get cut off (or spill under their
 * boxes) when a Riposte-style theme swaps in JetBrains Mono — the mono glyphs are ~25%
 * wider at the same sp.
 *
 * Fit is found by stepping the scale down [SCALE_STEP] at a time from the style's size until
 * the layout no longer overflows or [minScale] is reached; only past that floor does the
 * ellipsis appear. The text is not drawn until a fitting scale is found, so there is no
 * visible "shrink flicker" on first composition. Compose foundation 1.8 ships this natively
 * (`TextAutoSize`); replace this with that when the BOM moves.
 */
@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    minScale: Float = 0.62f,
) {
    // Re-fit from full size whenever the label or its base style changes.
    var scale by remember(text, style, fontSize, maxLines) { mutableFloatStateOf(1f) }
    var fitted by remember(text, style, fontSize, maxLines) { mutableStateOf(false) }
    val baseSize = when {
        fontSize.isSpecified -> fontSize
        style.fontSize.isSpecified -> style.fontSize
        else -> FALLBACK_FONT_SIZE
    }
    Text(
        text = text,
        modifier = modifier.drawWithContent { if (fitted) drawContent() },
        color = color,
        style = style,
        fontSize = baseSize * scale,
        // The style's lineHeight is in sp for the unscaled font; pin it to the scaled size so
        // shrunk text doesn't sit low in (or overflow) a box sized off the original height.
        lineHeight = baseSize * scale * LINE_HEIGHT_RATIO,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if ((layout.didOverflowWidth || layout.didOverflowHeight) && scale > minScale) {
                scale = (scale - SCALE_STEP).coerceAtLeast(minScale)
            } else {
                fitted = true
            }
        },
    )
}

private val FALLBACK_FONT_SIZE = 16.sp
private const val SCALE_STEP = 0.05f
private const val LINE_HEIGHT_RATIO = 1.25f
