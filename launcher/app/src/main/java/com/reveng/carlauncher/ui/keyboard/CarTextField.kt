package com.reveng.carlauncher.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reveng.carlauncher.ui.LocalParkedOnlyLock
import com.reveng.carlauncher.ui.theme.carShape
import com.reveng.carlauncher.ui.theme.DISABLED_ALPHA
import com.reveng.carlauncher.ui.withTapFeedback

/**
 * v2.7 — the launcher's text input, replacing `OutlinedTextField` everywhere in the app.
 *
 * This is deliberately *not* a real text field. A focusable `BasicTextField` summons the vendor
 * IME, which is the thing we are trying to get away from — it ignores night mode, cannot be
 * themed, and covers half of a 720px screen. So the field on the page is a display-only tile, and
 * tapping it opens a full-screen editor that owns its own [CarKeyboard]. Nothing in the app ever
 * takes IME focus, so the system keyboard has no way to appear.
 *
 * What that costs, honestly: no caret placement, no text selection, no clipboard, no autocomplete.
 * Editing is append-and-backspace. For a theme name, a hex triplet and a SysVar value — the three
 * things this app asks anyone to type — that is a fair trade for a field that themes correctly and
 * leaves the screen visible.
 *
 * v2.5 §1.4 comes along for free: the field is inert while the parked-only lock holds, and an open
 * editor closes itself if the car pulls away. Every text field in the app is motion-gated by
 * construction rather than by remembering to wrap each one.
 */

/** When the caller's [CarTextField] `onValueChange` fires. */
enum class CommitMode {
    /** Every keystroke. For live-filtered fields (search boxes, the hex colour field). */
    LIVE,

    /** Only when the editor is confirmed. For fields whose write has a cost — a SysVar put. */
    ON_DONE,
}

@Composable
fun CarTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    commit: CommitMode = CommitMode.LIVE,
) {
    var editing by remember { mutableStateOf(false) }
    // The value as it was when the editor opened. In LIVE mode every keystroke has already gone
    // through onValueChange by the time Cancel (or the motion-gate force-close) fires, so cancel
    // must put this back — otherwise "Cancel" kept the last keystrokes committed.
    var valueOnOpen by remember { mutableStateOf(value) }
    val locked = LocalParkedOnlyLock.current

    val borderColor = if (locked) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = BORDER_ALPHA)
    }

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FIELD_HEIGHT_DP.dp)
                .clip(carShape(FIELD_CORNER_DP.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, borderColor, carShape(FIELD_CORNER_DP.dp))
                .clickable(enabled = !locked) {
                    valueOnOpen = value
                    editing = true
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = displayText(value, placeholder, locked),
                style = MaterialTheme.typography.bodyLarge,
                color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (!editing) {
        return
    }
    CarKeyboardEditor(
        title = label ?: placeholder,
        initial = value,
        onLive = { if (commit == CommitMode.LIVE) onValueChange(it) },
        onDone = { onValueChange(it); editing = false },
        onCancel = {
            // LIVE has already committed keystroke by keystroke; cancel means none of them.
            if (commit == CommitMode.LIVE) {
                onValueChange(valueOnOpen)
            }
            editing = false
        },
    )
}

/** Hint text: while locked the field says why it does nothing rather than looking broken. */
private fun displayText(value: String, placeholder: String, locked: Boolean): String {
    if (value.isNotEmpty()) {
        return value
    }
    if (locked) {
        return "Available when parked"
    }
    return placeholder
}

/**
 * The full-screen editor: title, the text being edited with a block cursor, Cancel / Done, and the
 * keyboard. Full-screen rather than a small dialog because the keyboard needs the width, and a
 * half-covered parent screen is exactly what the vendor IME already does badly.
 */
@Composable
fun CarKeyboardEditor(
    title: String,
    initial: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    onLive: (String) -> Unit = {},
) {
    var draft by remember { mutableStateOf(initial) }

    // v2.5 §1.4: if the car pulls away mid-edit we abandon the edit rather than swapping a notice
    // in behind a modal. Discarding is the safe outcome — a half-typed SysVar value is worse than
    // no value, and the screen underneath is somewhere sane to land.
    val locked = LocalParkedOnlyLock.current
    LaunchedEffect(locked) {
        if (locked) {
            onCancel()
        }
    }

    fun edit(next: String) {
        draft = next
        onLive(next)
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                EditorHeader(
                    title = title,
                    draft = draft,
                    onCancel = onCancel,
                    onDone = { onDone(draft) },
                )

                Spacer(Modifier.weight(1f))

                CarKeyboard(
                    onChar = { edit(draft + it) },
                    onBackspace = { edit(draft.dropLast(1)) },
                    onEnter = { onDone(draft) },
                    enterLabel = DONE_LABEL,
                )
            }
        }
    }
}

@Composable
private fun EditorHeader(
    title: String,
    draft: String,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = draft,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Block cursor: with no real text field there is no system caret, and a line of text
            // with nothing after it reads as "this field is not accepting input".
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .width(CURSOR_WIDTH_DP.dp)
                    .height(CURSOR_HEIGHT_DP.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.weight(1f))
            EditorButton(label = "Cancel", filled = false, onClick = onCancel)
            Spacer(Modifier.width(12.dp))
            EditorButton(label = "Done", filled = true, onClick = onDone)
        }
    }
}

@Composable
private fun EditorButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val press = withTapFeedback(onClick)
    val background = if (filled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (filled) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .clip(carShape(FIELD_CORNER_DP.dp))
            .background(background)
            .clickable(onClick = press)
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private const val FIELD_HEIGHT_DP = 52
private const val FIELD_CORNER_DP = 12
private const val CURSOR_WIDTH_DP = 4
private const val CURSOR_HEIGHT_DP = 34

private const val BORDER_ALPHA = 0.6f

private const val DONE_LABEL = "Done"
