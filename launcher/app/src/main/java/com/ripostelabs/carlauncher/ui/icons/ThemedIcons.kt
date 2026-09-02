package com.ripostelabs.carlauncher.ui.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.ripostelabs.carlauncher.AppInfo
import com.ripostelabs.carlauncher.R
import com.ripostelabs.carlauncher.ui.theme.ThemeStyle
import kotlin.math.roundToInt

/**
 * Rasterises a themed app icon: the app's monochrome layer, or a generated letter, tinted with
 * the active palette on a plate. Bitmaps are cached by [IconKey], so a theme, a size or a
 * day/night flip renders each icon once and a scroll through the drawer renders nothing.
 */
internal object ThemedIcons {

    private const val CACHE_ENTRIES = 96

    /**
     * AdaptiveIconDrawable draws its layers at 1.5x the icon bounds (the 108dp canvas around a
     * 72dp icon). The suite glyphs fill 50% of the layer, so 1.2x puts them at 60% of the tile,
     * the same weight the untinted tile gives its glyph.
     */
    private const val MONO_LAYER_SCALE = 1.2f

    private const val LETTER_SCALE = 0.55f

    /** Same radius as the untinted tile in AppIcon, before the theme's corner scale. */
    private const val CORNER_FRACTION = 0.22f

    /** RL-BRAND-001: "2px solid — structural borders". */
    private const val BORDER_DP = 2f

    private val cache = LruCache<IconKey, ImageBitmap>(CACHE_ENTRIES)

    /** The palette roles an icon is painted with, read once per theme change. */
    fun lookFor(scheme: ColorScheme, style: ThemeStyle): IconLook = IconLook(
        glyph = scheme.onSurface.toArgb(),
        letter = scheme.primary.toArgb(),
        plate = scheme.surfaceVariant.toArgb(),
        border = scheme.onBackground.toArgb(),
        cornerScale = style.cornerScale,
        hardEdge = style.hardEdge,
        monoType = style.monoType,
    )

    fun source(app: AppInfo): IconSource =
        IconPolicy.source(app.packageName, if (monoLayer(app.icon) == null) MonoLayer.ABSENT else MonoLayer.PRESENT)

    fun bitmap(context: Context, app: AppInfo, look: IconLook, sizePx: Int, density: Float): ImageBitmap {
        val key = IconKey(app.packageName + "/" + app.activityName, sizePx, look)
        cache.get(key)?.let { return it }

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawPlate(canvas, look, sizePx, density)

        val mono = monoLayer(app.icon)
        if (mono != null) {
            drawMono(canvas, mono, look, sizePx)
        } else {
            drawLetter(context, canvas, app.label, look, sizePx)
        }

        val image = bmp.asImageBitmap()
        cache.put(key, image)
        return image
    }

    private fun monoLayer(icon: Drawable): Drawable? = (icon as? AdaptiveIconDrawable)?.monochrome

    private fun drawPlate(canvas: Canvas, look: IconLook, sizePx: Int, density: Float) {
        val radius = sizePx * CORNER_FRACTION * look.cornerScale
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.plate }
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, fill)
        if (!look.hardEdge) {
            return
        }

        // Stroke centred on the edge would be half clipped; inset it by half its width.
        val width = BORDER_DP * density
        val half = width / 2f
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = look.border
            style = Paint.Style.STROKE
            strokeWidth = width
        }
        canvas.drawRoundRect(RectF(half, half, sizePx - half, sizePx - half), radius, radius, border)
    }

    private fun drawMono(canvas: Canvas, mono: Drawable, look: IconLook, sizePx: Int) {
        // A fresh instance: setTint on the shared drawable would recolour the icon for every
        // other reader of PackageManager's copy.
        val layer = (mono.constantState?.newDrawable() ?: mono).mutate()
        layer.setTint(look.glyph)
        val side = (sizePx * MONO_LAYER_SCALE).roundToInt()
        val offset = (sizePx - side) / 2
        layer.setBounds(offset, offset, offset + side, offset + side)
        layer.draw(canvas)
    }

    private fun drawLetter(context: Context, canvas: Canvas, label: String, look: IconLook, sizePx: Int) {
        val face = if (look.monoType) {
            ResourcesCompat.getFont(context, R.font.jetbrains_mono_extrabold) ?: Typeface.DEFAULT_BOLD
        } else {
            Typeface.DEFAULT_BOLD
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = look.letter
            typeface = face
            textSize = sizePx * LETTER_SCALE
            textAlign = Paint.Align.CENTER
        }
        // Optical centre: the glyph box sits between ascent and descent, not on the baseline.
        val fm = paint.fontMetrics
        val baseline = sizePx / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(IconPolicy.letterFor(label), sizePx / 2f, baseline, paint)
    }
}
