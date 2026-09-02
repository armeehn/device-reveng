package com.ripostelabs.carlauncher.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.ui.theme.carShape
import com.ripostelabs.carlauncher.ui.withTapFeedback

/**
 * v2.7 — the launcher's own on-screen keyboard, extracted from the v2.3 search overlay so every
 * text field in the app can use it (`CarTextField`), not just app search.
 *
 * Why we draw our own keys at all: the vendor IME is a separate system app that themes off the
 * system `uiMode`, not our [MaterialTheme.colorScheme]. It ignores night mode on this unit, cannot
 * be reskinned, and eats roughly half of a 720px-tall screen when it opens. Every pixel here comes
 * from the active `CarTheme`, and the caller decides how much room the keyboard gets.
 *
 * v2.3's version had no shift and no symbols because app labels only ever need letters. That is no
 * longer true: a SysVar value is free text over a live vehicle config table, and a theme name is
 * whatever the user types. So this one carries a [ShiftState] and a [KeyboardLayer].
 *
 * The symbol layer is a *curated* set, not the full ASCII table: the punctuation that actually
 * turns up in vendor keys and paths. Anything outside it has to be pasted in over adb — a
 * deliberate trade for keys that stay 80dp+ wide and hittable in a moving car.
 */

/** Which page of keys is showing. Letters is always the entry point. */
enum class KeyboardLayer { LETTERS, SYMBOLS }

/**
 * Shift latch. [ONCE] auto-releases after one character (the usual "capitalise this letter"),
 * [LOCKED] survives until tapped off — needed for the all-caps vendor key names in the SysVar
 * browser, where releasing after every letter would be maddening.
 */
enum class ShiftState { OFF, ONCE, LOCKED }

/**
 * A full-width themed QWERTY.
 *
 * @param onChar receives the character as it should be inserted — shift and layer are resolved
 *   here, so callers never re-implement case handling.
 * @param enterLabel what the ⏎ key says. The search overlay launches with it, a text field
 *   commits with it, and saying which keeps the driver from guessing.
 */
@Composable
fun CarKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
    enterLabel: String = ENTER_GLYPH,
) {
    var layer by remember { mutableStateOf(KeyboardLayer.LETTERS) }
    var shift by remember { mutableStateOf(ShiftState.OFF) }

    // One funnel for every character key: apply the shift latch, then release it if it was
    // a one-shot. Doing this per-key site is how the case bugs get in. Punctuation has no case,
    // so the symbol layer bypasses the latch entirely rather than relying on uppercaseChar()
    // happening to be an identity for it.
    fun emit(c: Char) {
        if (layer == KeyboardLayer.SYMBOLS) {
            onChar(c)
            return
        }
        onChar(if (shift == ShiftState.OFF) c.lowercaseChar() else c.uppercaseChar())
        if (shift == ShiftState.ONCE) {
            shift = ShiftState.OFF
        }
    }

    Column(
        modifier = modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        KeyRow {
            DIGIT_ROW.forEach { c -> CarKey(c.toString()) { onChar(c) } }
        }

        val topRow = if (layer == KeyboardLayer.LETTERS) LETTER_ROW_TOP else SYMBOL_ROW_TOP
        KeyRow {
            topRow.forEach { c -> CarKey(displayOf(c, layer, shift)) { emit(c) } }
        }

        val midRow = if (layer == KeyboardLayer.LETTERS) LETTER_ROW_MID else SYMBOL_ROW_MID
        KeyRow {
            midRow.forEach { c -> CarKey(displayOf(c, layer, shift)) { emit(c) } }
            CarKey(BACKSPACE_GLYPH, weight = WIDE_KEY_WEIGHT, onPress = onBackspace)
        }

        val botRow = if (layer == KeyboardLayer.LETTERS) LETTER_ROW_BOTTOM else SYMBOL_ROW_BOTTOM
        KeyRow {
            // Shift is only meaningful on the letter layer. Rather than leave the slot dead (or
            // duplicate the layer toggle two keys along), symbols spend it on the backslash — the
            // one mark that would otherwise need adb, and the one a file path always wants.
            if (layer == KeyboardLayer.LETTERS) {
                CarKey(
                    label = SHIFT_GLYPH,
                    weight = WIDE_KEY_WEIGHT,
                    accented = shift != ShiftState.OFF,
                ) {
                    shift = nextShift(shift)
                }
            } else {
                CarKey(BACKSLASH, weight = WIDE_KEY_WEIGHT) { onChar('\\') }
            }

            botRow.forEach { c -> CarKey(displayOf(c, layer, shift)) { emit(c) } }

            // One toggle, labelled with where it goes — "?123" out of letters, "ABC" back.
            CarKey(
                label = if (layer == KeyboardLayer.LETTERS) SYMBOLS_LABEL else LETTERS_LABEL,
                weight = WIDE_KEY_WEIGHT,
            ) {
                layer = if (layer == KeyboardLayer.LETTERS) KeyboardLayer.SYMBOLS
                else KeyboardLayer.LETTERS
            }
            CarKey(SPACE_GLYPH, weight = SPACE_KEY_WEIGHT) { onChar(' ') }
            CarKey(enterLabel, weight = WIDE_KEY_WEIGHT, onPress = onEnter)
        }
    }
}

/** Cycle OFF → ONCE → LOCKED → OFF, so one key reaches all three states. */
private fun nextShift(current: ShiftState): ShiftState = when (current) {
    ShiftState.OFF -> ShiftState.ONCE
    ShiftState.ONCE -> ShiftState.LOCKED
    ShiftState.LOCKED -> ShiftState.OFF
}

/** What a character key prints on its face — letters follow the shift latch, symbols never do. */
private fun displayOf(c: Char, layer: KeyboardLayer, shift: ShiftState): String {
    if (layer == KeyboardLayer.SYMBOLS) {
        return c.toString()
    }
    return if (shift == ShiftState.OFF) c.lowercaseChar().toString() else c.uppercaseChar().toString()
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        content = content,
    )
}

/**
 * One key. [accented] draws it in the primary colour — the only way a latched shift is visible,
 * since the key glyph itself cannot change.
 */
@Composable
private fun RowScope.CarKey(
    label: String,
    weight: Float = 1f,
    accented: Boolean = false,
    onPress: () -> Unit,
) {
    // v2.5 §1.4: keys are struck in quick succession without looking, so each accepted press
    // gets an eyes-free confirmation.
    val press = withTapFeedback(onPress)
    val background = if (accented) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (accented) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(weight)
            .height(KEY_HEIGHT_DP.dp)
            .clip(carShape(KEY_CORNER_DP.dp))
            .background(background)
            .clickable(onClick = press),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = foreground,
        )
    }
}

// ---- layout ---------------------------------------------------------------------------

/** Sized for a gloved thumb on the 1920x720 panel; four of these plus gaps fit under a result row. */
private const val KEY_HEIGHT_DP = 72
private const val KEY_CORNER_DP = 10
private const val ROW_GAP_DP = 6

/** ⌫ / ⇧ / ?123 / ⏎ are all one-and-a-half keys wide; space is the only really wide one. */
private const val WIDE_KEY_WEIGHT = 1.5f
private const val SPACE_KEY_WEIGHT = 2.5f

private const val DIGIT_ROW = "1234567890"
private const val LETTER_ROW_TOP = "QWERTYUIOP"
private const val LETTER_ROW_MID = "ASDFGHJKL"
private const val LETTER_ROW_BOTTOM = "ZXCVBNM"

/** Curated punctuation: what shows up in vendor SysVar keys, values and paths. */
private const val SYMBOL_ROW_TOP = "!@#$%^&*()"
private const val SYMBOL_ROW_MID = "-_=+[]{}|"
private const val SYMBOL_ROW_BOTTOM = ",.:;'\"?/"
// Deliberately unreachable: angle brackets, tilde, backtick. Push those in over adb.

private const val BACKSPACE_GLYPH = "⌫"
private const val SHIFT_GLYPH = "⇧"
private const val BACKSLASH = "\\"
private const val SPACE_GLYPH = "␣"
private const val ENTER_GLYPH = "⏎"
private const val SYMBOLS_LABEL = "?123"
private const val LETTERS_LABEL = "ABC"
