package com.ripostelabs.carlauncher.ui.icons

/**
 * The pure half of themed app icons: which of three renderings a package gets, the fallback
 * letter, and the cache key. Nothing here touches Android, so it is unit-tested on the JVM.
 *
 *                 themedIcons on
 *   package ──► keepsRealIcon? ──yes──► REAL        (CarPlay, vendor safety apps)
 *                   │no
 *                   ▼
 *              monochrome layer? ──yes──► MONOCHROME (tinted with the palette)
 *                   │no
 *                   ▼
 *                 LETTER                             (first letter on a plate)
 */
enum class IconSource { REAL, MONOCHROME, LETTER }

/** Whether the app's adaptive icon ships an API 33 `<monochrome>` layer. */
enum class MonoLayer { PRESENT, ABSENT }

/**
 * Everything a tinted icon depends on, as plain values. Colours are packed ARGB ints taken
 * from the theme's *target* scheme, never the animated one: a crossfade would otherwise mint
 * a new bitmap every frame for 420 ms.
 */
data class IconLook(
    val glyph: Int,
    val letter: Int,
    val plate: Int,
    val border: Int,
    val cornerScale: Float,
    val hardEdge: Boolean,
    val monoType: Boolean,
)

/** Cache key: one bitmap per component, per pixel size, per look. */
data class IconKey(val component: String, val sizePx: Int, val look: IconLook)

object IconPolicy {

    /** Shown when a label has no letter or digit to lead with. */
    const val NO_LETTER = "#"

    /**
     * Apps whose real icon is part of how the driver recognises a safety-relevant surface.
     * Zlink is the CarPlay/Android Auto receiver; Android Auto's own tile can appear too.
     */
    private val keepRealIcon = setOf(
        "com.zjinnova.zlink",
        "com.google.android.projection.gearhead",
    )

    /** Vendor packages (reverse camera, CAN tools, factory menus) — never restyled. */
    private val keepRealPrefixes = listOf(
        "com.szchoiceway.",
        "com.choiceway.",
        "com.lfg.szchoiceway.",
        "com.syu.",
        "com.ivicar.",
    )

    fun keepsRealIcon(packageName: String): Boolean =
        packageName in keepRealIcon || keepRealPrefixes.any { packageName.startsWith(it) }

    fun source(packageName: String, mono: MonoLayer): IconSource {
        if (keepsRealIcon(packageName)) {
            return IconSource.REAL
        }
        if (mono == MonoLayer.PRESENT) {
            return IconSource.MONOCHROME
        }
        return IconSource.LETTER
    }

    /** The first letter or digit of [label], upper-cased; e.g. "Device Info" → "D". */
    fun letterFor(label: String): String {
        val lead = label.firstOrNull { it.isLetterOrDigit() } ?: return NO_LETTER
        return lead.uppercase()
    }
}
