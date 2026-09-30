package com.ripostelabs.carlauncher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Which half of a theme an image belongs to. */
enum class Phase { DAY, NIGHT }

/**
 * RAV4-196 — an optional home wallpaper per theme, with a day and a night image.
 *
 * The picked image is copied into app storage (`files/wallpapers/<theme>.<phase>.img`), so it
 * survives the source file being deleted and needs no persisted URI grant. Built-in themes are
 * immutable, so the image is keyed by theme id beside the theme rather than inside its JSON.
 *
 * ```
 *   picker ──uri──▶ import() ──copy──▶ files/wallpapers/builtin.riposte.night.img
 *                                              │
 *   HomeScreen ◀── load(themeId, NIGHT) ◀──────┘  (falls back to the day image)
 * ```
 */
class WallpaperStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, DIR_NAME)
    private val resolver = context.applicationContext.contentResolver

    // Bumped on every change so Home reloads the bitmap without watching the directory.
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** True when the theme has an image for [phase] of its own. */
    fun has(themeId: String, phase: Phase): Boolean = file(themeId, phase).isFile

    /** Copy the picked image in. False when it cannot be read or is too large. */
    suspend fun import(themeId: String, phase: Phase, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = file(themeId, phase)
        val tmp = File(dir, target.name + ".part")

        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> copyCapped(input, out) }
            } ?: false
        }.getOrDefault(false)

        // Keep the old image unless the new one arrived whole.
        if (!copied || !tmp.renameTo(target)) {
            tmp.delete()
            return@withContext false
        }
        _version.value++
        true
    }

    /** Remove both images of a theme. */
    fun clear(themeId: String) {
        Phase.entries.forEach { file(themeId, it).delete() }
        _version.value++
    }

    /** Decode the image for [phase] (or the other phase's), sampled to about panel size. */
    suspend fun load(themeId: String, phase: Phase): Bitmap? = withContext(Dispatchers.IO) {
        val shown = pick(phase, has(themeId, Phase.DAY), has(themeId, Phase.NIGHT))
            ?: return@withContext null
        val path = file(themeId, shown).path

        // Read the size first, then decode at the smallest power of two that still covers it.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PANEL_WIDTH_PX, PANEL_HEIGHT_PX)
        }
        runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }

    private fun file(themeId: String, phase: Phase): File =
        File(dir, "${safeName(themeId)}.${phase.name.lowercase()}.img")

    private fun copyCapped(input: java.io.InputStream, out: java.io.OutputStream): Boolean {
        val buf = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) {
                return total > 0
            }
            total += n
            if (total > MAX_BYTES) {
                return false
            }
            out.write(buf, 0, n)
        }
    }

    companion object {
        private const val DIR_NAME = "wallpapers"

        /** The GT6 panel. A bigger decode only costs memory the launcher shares with maps. */
        const val PANEL_WIDTH_PX = 1920
        const val PANEL_HEIGHT_PX = 720

        /** A 12 MP JPEG is about 5 MB. Anything past this is not a photo worth decoding. */
        private const val MAX_BYTES = 25L * 1024 * 1024
        private const val COPY_BUFFER = 64 * 1024

        /** Theme ids are free text for user themes. Keep them from escaping the directory. */
        fun safeName(themeId: String): String = themeId.replace(Regex("[^A-Za-z0-9._-]"), "_")

        /** The phase whose image to show: its own, else the other one, else none. */
        fun pick(phase: Phase, hasDay: Boolean, hasNight: Boolean): Phase? {
            val own = if (phase == Phase.DAY) hasDay else hasNight
            if (own) {
                return phase
            }
            val other = if (phase == Phase.DAY) Phase.NIGHT else Phase.DAY
            val hasOther = if (other == Phase.DAY) hasDay else hasNight
            return if (hasOther) other else null
        }

        /** Largest power of two that keeps the decoded image at least [maxW] x [maxH]. */
        fun sampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
            var size = 1
            while (w / (size * 2) >= maxW && h / (size * 2) >= maxH) {
                size *= 2
            }
            return size
        }
    }
}
