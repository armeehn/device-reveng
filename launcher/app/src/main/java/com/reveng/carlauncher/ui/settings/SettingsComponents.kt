package com.reveng.carlauncher.ui.settings

import com.reveng.carlauncher.ui.theme.carCard
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.input.focusRing // v2.8
import kotlin.math.roundToInt

/**
 * v1.1 — the reskinned settings component kit.
 *
 * These are the building blocks every settings category screen (Display, Reverse camera,
 * Audio, Climate, Radio, SWC, Power, System…) is assembled from. They mirror the *shape* of
 * the vendor GT6 settings rows (a labelled row with a trailing switch / value / picker / chevron)
 * but are drawn entirely from our [com.reveng.carlauncher.ui.theme.CarTheme] palette so the whole
 * suite reskins with the launcher instead of the vendor's fixed blue-on-grey look.
 *
 * Layout targets the 1920x720 head unit: generous 56dp+ touch rows, 24dp gutters, rounded
 * 18dp cards — big enough to hit while driving, consistent with [com.reveng.carlauncher.ui.SettingsScreen].
 */

/** Standard screen frame: themed header with a back tile + a scrolling body. */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIconTile(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", onClick = onBack)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            content()
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** A rounded, tappable icon button (back / home) matching the launcher's tiles. */
@Composable
fun SettingsIconTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(carShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .focusRing(cornerRadiusDp = 14) // v2.8
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** A titled card grouping related rows (the vendor groups settings the same way). */
@Composable
fun SettingsSection(title: String? = null, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .carCard()
            .clip(carShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(4.dp))
        }
        content()
    }
}

/** Navigational category tile on the settings hub: icon, title, subtitle, chevron. */
@Composable
fun SettingsCategoryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .carCard()
            .clip(carShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .focusRing(cornerRadiusDp = 18) // v2.8
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(carShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Base row: a label (+ optional description) on the left, arbitrary trailing content. */
@Composable
fun SettingRow(
    label: String,
    description: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
    // v2.8: the roving ring needs somewhere to land on every settings screen; adding it to the
    // shared row is what makes the whole suite wheel-drivable without twelve per-screen models.
    val clickable = if (onClick != null && enabled) {
        base.focusRing().clickable(onClick = onClick)
    } else {
        base
    }
    Row(
        modifier = clickable.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

/** Boolean toggle row. */
@Composable
fun ToggleSetting(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    SettingRow(
        label = label,
        description = description,
        enabled = enabled,
        onClick = { if (enabled) onChange(!checked) },
    ) {
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/**
 * Integer slider row. [value] is the current int; [range] its bounds; [step] the granularity.
 * [format] renders the trailing value badge (e.g. "60%", "12", "30 km/h").
 */
@Composable
fun SliderSetting(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    description: String? = null,
    step: Int = 1,
    enabled: Boolean = true,
    format: (Int) -> String = { it.toString() },
) {
    // Local echo so the thumb tracks the finger smoothly; committed on release.
    var live by remember(value) { mutableStateOf(value.toFloat()) }
    val steps = if (step > 0 && range.last > range.first) {
        ((range.last - range.first) / step - 1).coerceAtLeast(0)
    } else 0
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ValueBadge(format(live.roundToInt()))
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onChange(live.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

/**
 * Enum picker row. Shows the current option's label; tapping opens a themed radio dialog of
 * [options] (value → label). Calls [onSelect] with the chosen value.
 */
@Composable
fun <T> PickerSetting(
    label: String,
    current: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: "—"
    SettingRow(
        label = label,
        description = description,
        enabled = enabled,
        onClick = { if (enabled) open = true },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (open) {
        OptionPickerDialog(
            title = label,
            options = options,
            current = current,
            onSelect = { onSelect(it); open = false },
            onDismiss = { open = false },
        )
    }
}

/** Read-only info row: label on the left, static value on the right. */
@Composable
fun InfoRow(label: String, value: String) {
    SettingRow(label = label) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Primary action row (e.g. "Learn key", "Reboot", "Factory reset"). */
@Composable
fun ActionRow(
    label: String,
    onClick: () -> Unit,
    description: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing() // v2.8
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * v2.1 — a redesigned volume slider tuned for the OEM per-source gains: a leading source icon,
 * a value badge, and a track flanked by big −/+ stepper buttons so it's usable while driving.
 * Optimistic local echo; commits [onChange] on release and on each step.
 */
@Composable
fun VolumeSlider(
    icon: ImageVector,
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    enabled: Boolean = true,
    format: (Int) -> String = { it.toString() },
) {
    var live by remember(value) { mutableStateOf(value.toFloat()) }
    val steps = if (step > 0 && range.last > range.first) {
        ((range.last - range.first) / step - 1).coerceAtLeast(0)
    } else 0

    fun commit(v: Int) {
        val c = v.coerceIn(range.first, range.last)
        live = c.toFloat()
        onChange(c)
    }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(carShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ValueBadge(format(live.roundToInt()))
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", enabled) { commit(live.roundToInt() - step) }
            Slider(
                value = live,
                onValueChange = { live = it },
                onValueChangeFinished = { onChange(live.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = steps,
                enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            StepButton("+", enabled) { commit(live.roundToInt() + step) }
        }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(carShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A filled/outlined text button for dialogs, drawn from the theme palette. */
@Composable
fun DialogTextButton(
    label: String,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val bg = when {
        !filled -> MaterialTheme.colorScheme.surfaceVariant
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .clip(carShape(14.dp))
            .background(bg)
            .focusRing() // v2.8
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

/** Small rounded pill showing a value next to a slider. */
@Composable
fun ValueBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(carShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
