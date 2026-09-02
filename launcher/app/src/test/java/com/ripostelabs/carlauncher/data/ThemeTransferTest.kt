package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.ui.theme.BuiltInThemes
import com.ripostelabs.carlauncher.ui.theme.CarTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The import half of [ThemeTransfer] reads a file a human edited on a computer and pushed over adb,
 * which is the least trustworthy input the launcher takes. Two properties matter beyond "the
 * colours arrive":
 *
 *  * an imported theme is always a **new user theme** — a file claiming `builtin.midnight` must not
 *    shadow the preset or overwrite a theme already on the unit, and
 *  * it must not be able to claim `isBuiltIn`, which is the flag that makes the editor refuse edits.
 *
 * Only [ThemeTransfer.import] is exercised here: every other entry point needs an Android `Context`
 * for the external files directory. See the PR body.
 */
class ThemeTransferTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fileWith(text: String): File =
        temp.newFile().apply { writeText(text) }

    private fun exportedFileFor(theme: CarTheme): File =
        fileWith(theme.toJson().toString(2))

    @Test
    fun exportedFileImportsBackWithItsLookIntact() {
        val source = BuiltInThemes.MIDNIGHT

        val imported = ThemeTransfer.import(exportedFileFor(source))

        assertNotNull(imported)
        assertEquals(source.name, imported?.name)
        assertEquals(source.day, imported?.day)
        assertEquals(source.night, imported?.night)
        assertEquals(source.style, imported?.style)
    }

    @Test
    fun importCannotShadowAPreset() {
        // The file says builtin.midnight. Honouring that id would make the imported copy resolve
        // in place of the preset for anyone whose active theme is the preset.
        val imported = ThemeTransfer.import(exportedFileFor(BuiltInThemes.MIDNIGHT))

        assertNotEquals(BuiltInThemes.MIDNIGHT.id, imported?.id)
        assertEquals(true, imported?.id?.startsWith("user."))
    }

    @Test
    fun importCannotClaimToBeBuiltIn() {
        assertFalse(ThemeTransfer.import(exportedFileFor(BuiltInThemes.MIDNIGHT))!!.isBuiltIn)
    }

    @Test
    fun aStyleAddedByHandIsHonoured() {
        // The whole reason the file is pretty-printed is that people edit it. A hand-added style
        // block must take effect rather than be ignored in favour of the defaults.
        val text = BuiltInThemes.MIDNIGHT.toJson()
            .put(
                "style",
                org.json.JSONObject()
                    .put("cornerScale", 0.0)
                    .put("monoType", true)
                    .put("hardEdge", true),
            )
            .toString(2)

        val style = ThemeTransfer.import(fileWith(text))?.style

        assertEquals(0f, style?.cornerScale ?: -1f, 0f)
        assertEquals(true, style?.monoType)
        assertEquals(true, style?.hardEdge)
    }
}
