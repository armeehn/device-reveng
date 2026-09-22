package com.ripostelabs.carlauncher.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static car-UI guard over `ui/`. The runtime accessibility audit
 * (`androidTest/.../a11y/AccessibilityAuditTest`) only sees Home and the nine top-bar screens;
 * every settings sub-screen, dialog, overlay and onboarding page is invisible to it. These three
 * rules are the part of that audit a compiler-free read of the source can prove everywhere.
 *
 * Each rule carries an allow-list. An entry is a deliberate, reasoned exception with the reason
 * beside it — not a backlog. A new violation fails with the file, the line and what to do.
 */
class UiSourceAuditTest {

    /** §1.2's floor. Below this a target is missable by a thumb in a moving car. */
    private val minTargetDp = 48

    // ---- rule 1: touch targets ------------------------------------------------------------

    /**
     * Files whose sub-48 dp clickable is known and deliberately left. Reason per entry.
     *
     * QuickControls.kt — the status-bar "tune" icon (28 dp) and the row icon-actions (24 dp) are
     * real findings, reported in PR "Fix car-UI defects a static sweep finds". The file is owned
     * by an in-flight change, so fixing it here would collide.
     */
    private val smallTargetExempt = setOf("QuickControls.kt")

    @Test
    fun clickableTargetsAreNotSmallerThan48Dp() {
        val found = mutableListOf<String>()

        for (file in uiSources()) {
            if (file.name in smallTargetExempt) {
                continue
            }

            val lines = file.readTextLines()
            val constants = DP_CONSTANT.findAll(file.readText())
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }

            for ((index, line) in lines.withIndex()) {
                if (!CLICK_CALL.containsMatchIn(line)) {
                    continue
                }

                // Walk back up the modifier chain this .clickable belongs to, collecting the
                // sizing calls in it. Stop at the first line that is not a `.foo(...)` link, so
                // an unrelated chain above cannot lend its numbers to this one.
                val chain = StringBuilder(line)
                var cursor = index - 1
                while (cursor >= 0 && lines[cursor].trim().startsWith(".")) {
                    chain.append(' ').append(lines[cursor].trim())
                    cursor--
                }

                val smallest = SIZE_CALL.findAll(chain)
                    .flatMap { DP_VALUE.findAll(it.groupValues[2]) }
                    .mapNotNull { dpOf(it.groupValues[1], constants) }
                    .minOrNull()

                if (smallest != null && smallest < minTargetDp) {
                    found += "${file.name}:${index + 1} tap target is ${smallest}dp"
                }
            }
        }

        assertTrue(
            "Clickable smaller than ${minTargetDp}dp. Give it a named *_TARGET_DP constant of at " +
                "least $minTargetDp and grow the inner padding to keep the glyph size:\n" +
                found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    // ---- rule 2: colours ------------------------------------------------------------------

    /**
     * Files allowed to name a literal colour. Reason per entry.
     *
     * AppIcon.kt — the per-app tile palette. These are the apps' own brand colours, chosen so
     * two tiles never collide; a theme role would make every tile the same colour.
     * ThemeEditorScreen.kt — the R/G/B channel sliders are tinted red/green/blue because that is
     * what the channel *is*. Theming them would say nothing.
     */
    private val literalColorExempt = setOf("AppIcon.kt", "ThemeEditorScreen.kt")

    @Test
    fun coloursOutsideTheThemePackageComeFromTheTheme() {
        val found = mutableListOf<String>()

        for (file in uiSources()) {
            if (file.name in literalColorExempt) {
                continue
            }

            file.readTextLines().forEachIndexed { index, line ->
                if (LITERAL_COLOR.containsMatchIn(line)) {
                    found += "${file.name}:${index + 1} ${line.trim()}"
                }
            }
        }

        assertTrue(
            "Literal colour outside ui/theme. It survives no user theme and no night variant — " +
                "use a MaterialTheme.colorScheme role instead:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    // ---- rule 3: unlabelled icons ---------------------------------------------------------

    /**
     * Files whose `contentDescription = null` icons were read and judged decorative — each sits
     * beside its own visible label, or is a hero illustration the heading already names, so a
     * description would make a screen reader say the same thing twice. Reason per entry.
     *
     * The list is per file, not per line, so it survives edits inside a file; a file that is not
     * here has no unlabelled icon at all, and a new file gets read before it is added.
     */
    private val decorativeIconFiles = setOf(
        "ClimateCard.kt", // thermostat glyph on the "Climate unavailable" line
        "ContinueWatchingScreen.kt", // play glyph on a row whose title is the label
        "LauncherPrefsScreen.kt", // tick beside "Car Launcher is your default home."
        "MediaCard.kt", // album art and transport glyphs, each labelled by neighbouring text
        "MediaScreen.kt", // blurred art wash, album art, and the empty-art music note
        "NavCard.kt", // manoeuvre, speed and header glyphs beside their own readouts
        "OnboardingScreen.kt", // step hero icons and the glyph inside a labelled pill button
        "ParkedOnly.kt", // car glyph over the "Available when parked" heading
        "PhoneScreen.kt", // dial-pad and call-action glyphs beside their own labels
        "QuickControls.kt", // row icons; labelled only when the icon itself is tappable
        "RadioCard.kt", // band and preset glyphs beside the frequency readout
        "SearchOverlay.kt", // magnifier beside the query line and the search trigger's own text
        "SettingsComponents.kt", // row leading icons and the chevron; the row's title is the name
        "VideoMiniCard.kt", // movie glyph beside the video title
    )

    @Test
    fun onlyReviewedFilesLeaveAnIconUnlabelled() {
        val found = mutableListOf<String>()

        for (file in uiSources()) {
            if (file.name in decorativeIconFiles) {
                continue
            }

            file.readTextLines().forEachIndexed { index, line ->
                if (NULL_DESCRIPTION.containsMatchIn(line)) {
                    found += "${file.name}:${index + 1}"
                }
            }
        }

        assertTrue(
            "contentDescription = null in a file nobody has reviewed. If the icon is a control, " +
                "name it; if it only repeats an adjacent label, add the file to " +
                "decorativeIconFiles with the reason:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    // ---- rule 4: fallible actions ---------------------------------------------------------

    /**
     * Calls whose answer is "it did not happen", and which a screen used to drop on the floor:
     * `IntentSpec.start` returns false when nothing resolved, the two writers return null or
     * false when the write failed. Dropped, the button reads as broken instead of as blocked —
     * the exact shape of a btsuite page that is not installed, or of a full /sdcard.
     */
    private val fallibleCalls =
        Regex("""\)\.start\(context\)|\bLauncherBackup\.(create|restore)\(|\bSysVarExport\.export\(""")

    @Test
    fun aFallibleActionNeverDropsItsAnswer() {
        val found = mutableListOf<String>()

        for (file in uiSources()) {
            val lines = file.readTextLines()
            lines.forEachIndexed { index, line ->
                if (!fallibleCalls.containsMatchIn(line)) {
                    return@forEachIndexed
                }

                // The answer counts as used when the line binds it or branches on it. A call
                // wrapped in `val x = withContext(IO) { ... }` binds it one line up, so the
                // opener directly above counts too.
                val opener = lines.getOrNull(index - 1).orEmpty()
                val bound = ANSWER_USED.containsMatchIn(line) ||
                    (opener.trimEnd().endsWith("{") && ANSWER_USED.containsMatchIn(opener))
                if (bound) {
                    return@forEachIndexed
                }

                found += "${file.name}:${index + 1}: ${line.trim()}"
            }
        }

        assertTrue(
            "a fallible call's result is discarded. Bind it and tell the driver what did not " +
                "happen, rather than leaving a button that silently does nothing:\n" +
                found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    // ---- plumbing -------------------------------------------------------------------------

    /** Every Kotlin file under `ui/`, minus the theme package, which owns the literal colours. */
    private fun uiSources(): List<File> {
        var dir = File("").absoluteFile
        while (!File(dir, UI_PACKAGE_PATH).isDirectory) {
            dir = requireNotNull(dir.parentFile) {
                "ui/ sources not found above ${File("").absolutePath}"
            }
        }

        return File(dir, UI_PACKAGE_PATH).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.parentFile?.name == "theme" }
            .sortedBy { it.path }
            .toList()
    }

    private fun File.readTextLines(): List<String> = readText().split("\n")

    /** A dp literal, or a `private const val FOO_DP = 48` defined in the same file. */
    private fun dpOf(token: String, constants: Map<String, Int>): Int? =
        token.toIntOrNull() ?: constants[token]

    private companion object {
        const val UI_PACKAGE_PATH = "src/main/java/com/ripostelabs/carlauncher/ui"

        val CLICK_CALL = Regex("""\.(clickable|combinedClickable|toggleable)\s*[({]""")
        val SIZE_CALL = Regex("""\.(size|width|height|heightIn|sizeIn|defaultMinSize)\(([^)]*)\)""")
        val DP_VALUE = Regex("""([A-Za-z0-9_]+)\.dp""")
        val DP_CONSTANT = Regex("""const val ([A-Za-z0-9_]+)\s*=\s*(\d+)""")
        val LITERAL_COLOR = Regex("""\bColor\(0x[0-9A-Fa-f]""")
        val NULL_DESCRIPTION = Regex("""contentDescription\s*=\s*null""")
        val ANSWER_USED = Regex("""\b(val|var|return)\b|=\s*$|if\s*\(|\?:""")
    }
}
