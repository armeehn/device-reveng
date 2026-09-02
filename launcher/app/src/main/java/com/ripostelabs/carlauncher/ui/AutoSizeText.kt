package com.ripostelabs.carlauncher.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * A [Text] that shrinks its font to fit the box it was given before it resorts to an
 * ellipsis. Drop-in replacement for any label, value or title that shows data: app names,
 * track titles, frequencies, settings values. Sizes were tuned for the sans type scale and
 * get cut off (or spill under their boxes) when a Riposte-style theme swaps in JetBrains
 * Mono — the mono glyphs are ~25% wider at the same sp.
 *
 * Fit is found by [AutoSizeFit]: each layout pass that overflows (width, or height once
 * [maxLines] is used up) steps the scale down by a fixed ratio until it fits, [minScale] is
 * reached, or the pass budget is spent; only past that point does the ellipsis appear. The
 * text is not drawn until a fitting scale is found, so there is no visible shrink flicker,
 * and nothing runs per frame — once fitted the state stops changing. Compose foundation 1.8
 * ships this natively (`TextAutoSize`); replace this with that when the BOM moves.
 */
@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    minScale: Float = AutoSizeFit.DEFAULT_MIN_SCALE,
) {
    // Re-fit from full size whenever the label or its base style changes.
    var fit by remember(text, style, fontSize, maxLines) { mutableStateOf(AutoSizeFit.START) }
    val baseSize = when {
        fontSize.isSpecified -> fontSize
        style.fontSize.isSpecified -> style.fontSize
        else -> FALLBACK_FONT_SIZE
    }

    // The style's lineHeight is in sp for the unscaled font; pin it to the scaled size so
    // shrunk text doesn't sit low in (or overflow) a box sized off the original height.
    val baseLineHeight = if (lineHeight.isSpecified) lineHeight else baseSize * LINE_HEIGHT_RATIO

    Text(
        text = text,
        modifier = modifier.drawWithContent { if (fit.fitted) drawContent() },
        color = color,
        style = style,
        fontSize = baseSize * fit.scale,
        lineHeight = baseLineHeight * fit.scale,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            val overflowed = layout.didOverflowWidth || layout.didOverflowHeight
            fit = AutoSizeFit.advance(fit, overflowed, minScale)
        },
    )
}

private val FALLBACK_FONT_SIZE = 16.sp
private const val LINE_HEIGHT_RATIO = 1.25f
