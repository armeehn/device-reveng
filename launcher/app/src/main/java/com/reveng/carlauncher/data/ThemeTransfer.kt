package com.reveng.carlauncher.data

import android.content.Context
import android.util.Log
import com.reveng.carlauncher.ui.theme.CarTheme
import org.json.JSONObject
import java.io.File

/**
 * v2.7 — move a custom theme in and out of the launcher as a JSON file.
 *
 * ## Why files, and why *these* files
 *
 * A theme is ~30 colour values. Retyping one on the in-app keyboard is not a thing anyone will do,
 * and there is no second screen in a car to paste from — so the transfer has to be a file the
 * owner can reach over adb. It lands in the app's external files directory:
 *
 * ```
 * /sdcard/Android/data/com.reveng.carlauncher/files/themes/<id>.json
 * adb pull /sdcard/Android/data/com.reveng.carlauncher/files/themes/
 * adb push mytheme.json /sdcard/Android/data/com.reveng.carlauncher/files/themes/
 * ```
 *
 * That directory needs no runtime permission on API 33 and survives an app update. Rejected
 * alternatives: `Downloads` (scoped storage makes a plain `File` write there fail without
 * MediaStore or MANAGE_EXTERNAL_STORAGE — a lot of permission surface for a colour file), and a
 * `ACTION_CREATE_DOCUMENT` picker (the vendor's document UI is one more untheme-able system
 * screen, which is the problem this release exists to reduce).
 *
 * The codec is [CarTheme.toJson] / [themeFromJson] — the same one [ThemeStore] persists with, so an
 * exported file and a stored theme can never drift apart.
 */
object ThemeTransfer {

    private const val TAG = "ThemeTransfer"
    private const val DIR_NAME = "themes"
    private const val EXTENSION = ".json"

    /** Two-space JSON: these files are meant to be opened and hand-edited on a real computer. */
    private const val JSON_INDENT = 2

    /** The import/export directory, created on demand. Null if external storage is unavailable. */
    fun directory(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        val dir = File(base, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create ${dir.absolutePath}")
            return null
        }
        return dir
    }

    /** Write [theme] out. Returns the file so the UI can show the driver where it went. */
    fun export(context: Context, theme: CarTheme): File? {
        val dir = directory(context) ?: return null
        val file = File(dir, safeName(theme) + EXTENSION)
        return runCatching {
            file.writeText(theme.toJson().toString(JSON_INDENT))
            file
        }.onFailure { Log.w(TAG, "export failed: ${it.message}") }.getOrNull()
    }

    /** Every importable file currently sitting in the directory, newest first. */
    fun listImportable(context: Context): List<File> {
        val dir = directory(context) ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    /**
     * Read a theme file back.
     *
     * Always lands as a **new user theme**: a fresh id, and `isBuiltIn` forced false. An imported
     * file could otherwise claim `builtin.midnight` and shadow a preset, or collide with a theme
     * already on the unit and silently overwrite it. Import is additive by construction; the
     * driver deletes what they don't want from the Themes screen.
     *
     * Returns null on anything malformed — a hand-edited colour file is exactly the kind of input
     * that arrives half-written, and losing an import is a much better outcome than a crash loop
     * in the launcher that owns the HOME intent.
     */
    fun import(file: File): CarTheme? = runCatching {
        val parsed = themeFromJson(JSONObject(file.readText()))
        parsed.copy(
            id = "user.${System.currentTimeMillis()}",
            isBuiltIn = false,
        )
    }.onFailure { Log.w(TAG, "import ${file.name} failed: ${it.message}") }.getOrNull()

    /** Theme names are free text; a filename is not. Keep it recognisable and safe to write. */
    private fun safeName(theme: CarTheme): String {
        val cleaned = theme.name
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
        return cleaned.ifBlank { theme.id }
    }
}
