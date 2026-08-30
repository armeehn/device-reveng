package com.ripostelabs.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.ripostelabs.carlauncher.ui.theme.carShape
import com.ripostelabs.carlauncher.ui.theme.carCard
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.ui.keyboard.CarTextField // v2.7
import com.ripostelabs.carlauncher.ui.keyboard.CommitMode // v2.7
import com.ripostelabs.carlauncher.ui.theme.CarTheme
import com.ripostelabs.carlauncher.ui.theme.ThemeColors
import com.ripostelabs.carlauncher.ui.theme.ThemeStyle
import java.util.Locale

/**
 * The editable color roles, in display order. The two extra accents are optional:
 * 0 = unset, rendered as a fallback to [ThemeColors.primary] (see toColorScheme).
 */
private enum class Role(val label: String) {
    Background("Background"),
    Surface("Surface"),
    SurfaceVariant("Surface variant"),
    Primary("Primary / accent"),
    OnBackground("Text on background"),
    OnSurface("Text on surface"),
    OnSurfaceMuted("Muted text"),
    Error("Error"),
    Accent2("Accent 2"),
    Accent3("Accent 3"),
}

private val Role.isAccent: Boolean
    get() = this == Role.Accent2 || this == Role.Accent3

private fun ThemeColors.get(role: Role): Long = when (role) {
    Role.Background -> background
    Role.Surface -> surface
    Role.SurfaceVariant -> surfaceVariant
    Role.Primary -> primary
    Role.OnBackground -> onBackground
    Role.OnSurface -> onSurface
    Role.OnSurfaceMuted -> onSurfaceMuted
    Role.Error -> error
    Role.Accent2 -> accent2
    Role.Accent3 -> accent3
}

private fun ThemeColors.set(role: Role, value: Long): ThemeColors = when (role) {
    Role.Background -> copy(background = value)
    Role.Surface -> copy(surface = value)
    Role.SurfaceVariant -> copy(surfaceVariant = value)
    Role.Primary -> copy(primary = value)
    Role.OnBackground -> copy(onBackground = value)
    Role.OnSurface -> copy(onSurface = value)
    Role.OnSurfaceMuted -> copy(onSurfaceMuted = value)
    Role.Error -> copy(error = value)
    Role.Accent2 -> copy(accent2 = value)
    Role.Accent3 -> copy(accent3 = value)
}

/**
 * Theme editor: name field, a Day/Night variant toggle, a per-role color list with an
 * RGB + hex color picker, and a live preview swatch that updates as you edit. Save writes
 * the theme back through ThemeStore (persisted); Cancel discards.
 *
 * All state is local to the editor (a working copy of the [CarTheme]) so edits are only
 * committed on Save.
 */
@Composable
fun ThemeEditorScreen(
    source: CarTheme,
    night: Boolean,
    onSave: (CarTheme) -> Unit,
    onCancel: () -> Unit,
) {
    var working by remember(source.id) { mutableStateOf(source) }
    var editingNight by remember(source.id) { mutableStateOf(night) }
    var selectedRole by remember(source.id) { mutableStateOf(Role.Background) }

    val variant = working.variant(editingNight)
    // An unset accent (0) edits from the primary it currently falls back to.
    val selectedRaw = variant.get(selectedRole)
    val selectedColor =
        if (selectedRole.isAccent && selectedRaw == 0L) variant.primary else selectedRaw

    fun updateSelected(value: Long) {
        working = if (editingNight) {
            working.copy(night = working.night.set(selectedRole, value))
        } else {
            working.copy(day = working.day.set(selectedRole, value))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- header: back + name + save --------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Cancel", onClick = onCancel)
            Spacer(Modifier.width(16.dp))
            // v2.7: our own keyboard, not the vendor IME — see ui/keyboard/CarTextField.kt.
            CarTextField(
                value = working.name,
                onValueChange = { working = working.copy(name = it) },
                label = "Theme name",
                placeholder = "Untitled",
                modifier = Modifier.width(THEME_NAME_FIELD_DP.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButtonTile(label = "Save", onClick = { onSave(working) })
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ---- LEFT: role list + day/night toggle --------------------------------
            Column(
                modifier = Modifier
                    .weight(0.34f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VariantToggle(editingNight = editingNight, onChange = { editingNight = it })
                Spacer(Modifier.height(4.dp))
                Role.entries.forEach { role ->
                    val raw = variant.get(role)
                    val unset = role.isAccent && raw == 0L
                    RoleRow(
                        label = role.label,
                        color = Color(if (unset) variant.primary else raw),
                        hint = if (unset) "primary" else hexOf(raw),
                        selected = role == selectedRole,
                        onClick = { selectedRole = role },
                    )
                }

                // Non-color style. Persisted with the theme all along (ThemeStore /
                // ThemeTransfer carry ThemeStyle) but uneditable until now.
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Style",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                StyleEditor(
                    style = working.style,
                    onChange = { working = working.copy(style = it) },
                )
            }

            // ---- CENTER: color picker for the selected role ------------------------
            Column(
                modifier = Modifier
                    .weight(0.36f)
                    .fillMaxHeight(),
            ) {
                Text(
                    text = selectedRole.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ColorPicker(color = selectedColor, onColor = ::updateSelected)
                if (selectedRole.isAccent) {
                    Spacer(Modifier.height(12.dp))
                    ResetAccentRow(onReset = { updateSelected(0L) })
                }
            }

            // ---- RIGHT: live preview -----------------------------------------------
            Column(
                modifier = Modifier
                    .weight(0.30f)
                    .fillMaxHeight(),
            ) {
                Text(
                    text = "Live preview (${if (editingNight) "night" else "day"})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ThemePreviewSwatch(
                    colors = variant,
                    style = working.style,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
            }
        }
    }
}

@Composable
private fun VariantToggle(editingNight: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .carCard()
            .clip(carShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(4.dp),
    ) {
        SegItem("Day", !editingNight) { onChange(false) }
        SegItem("Night", editingNight) { onChange(true) }
    }
}

@Composable
private fun SegItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(carShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun RoleRow(
    label: String,
    color: Color,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .carCard()
            .clip(carShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(carShape(7.dp))
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, carShape(7.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Clears an accent back to 0 — "follow primary" — the state a fresh theme starts in. */
@Composable
private fun ResetAccentRow(onReset: () -> Unit) {
    Box(
        modifier = Modifier
            .carCard()
            .clip(carShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onReset)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Use primary (unset)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Corner-scale slider plus the two brand switches ([ThemeStyle]). */
@Composable
private fun StyleEditor(style: ThemeStyle, onChange: (ThemeStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Corners",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(CORNER_LABEL_DP.dp),
            )
            Slider(
                value = style.cornerScale,
                onValueChange = { onChange(style.copy(cornerScale = it)) },
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                text = if (style.cornerScale == 0f) "sharp"
                else "${(style.cornerScale * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(CORNER_VALUE_DP.dp),
            )
        }
        StyleSwitch("Mono type", style.monoType) { onChange(style.copy(monoType = it)) }
        StyleSwitch("Hard edges", style.hardEdge) { onChange(style.copy(hardEdge = it)) }
    }
}

@Composable
private fun StyleSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * A self-contained RGB color picker: a large swatch, red/green/blue sliders (0-255) and a
 * hex field, all two-way bound. No external library. Alpha is fixed opaque — a head-unit
 * surface is never translucent.
 */
@Composable
private fun ColorPicker(color: Long, onColor: (Long) -> Unit) {
    val r = (color.toInt() shr 16) and 0xFF
    val g = (color.toInt() shr 8) and 0xFF
    val b = color.toInt() and 0xFF

    // Local hex text so the user can type freely; committed when it parses to 6 hex digits.
    var hexText by remember(color) { mutableStateOf(hexOf(color)) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(carShape(12.dp))
                .background(Color(color))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, carShape(12.dp)),
        )

        ChannelSlider("R", r, Color(0xFFE5534B)) { onColor(pack(it, g, b)) }
        ChannelSlider("G", g, Color(0xFF3FB950)) { onColor(pack(r, it, b)) }
        ChannelSlider("B", b, Color(0xFF2F81F7)) { onColor(pack(r, g, it)) }

        // v2.7: CommitMode.LIVE keeps the v0.5 behaviour — the swatch tracks the digits as they
        // are typed, and a half-typed value simply doesn't parse yet.
        CarTextField(
            value = hexText,
            onValueChange = { input ->
                hexText = input
                parseHex(input)?.let(onColor)
            },
            label = "Hex (RRGGBB)",
            commit = CommitMode.LIVE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChannelSlider(label: String, value: Int, tint: Color, onValue: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            modifier = Modifier.width(20.dp),
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            text = value.toString().padStart(3, ' '),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
    }
}

@Composable
private fun TextButtonTile(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(carShape(12.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---- color helpers ---------------------------------------------------------------------

/** Wide enough for a real theme name without crowding the Save tile out of the header row. */
private const val THEME_NAME_FIELD_DP = 320

/** Fixed columns in the corner-scale row so the slider gets the leftover width. */
private const val CORNER_LABEL_DP = 84
private const val CORNER_VALUE_DP = 48

private fun pack(r: Int, g: Int, b: Int): Long =
    0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()

private fun hexOf(color: Long): String =
    String.format(Locale.US, "%06X", color.toInt() and 0xFFFFFF)

private fun parseHex(input: String): Long? {
    val cleaned = input.trim().removePrefix("#")
    if (cleaned.length != 6 || cleaned.any { it.digitToIntOrNull(16) == null }) return null
    return 0xFF000000L or cleaned.toLong(16)
}
