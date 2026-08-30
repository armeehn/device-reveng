package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.ui.theme.carCard
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.ripostelabs.carlauncher.ui.theme.carShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.RadioPreset
import com.ripostelabs.carlauncher.data.RadioPresetsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RadioCard — RIGHT column tuner readout + transport (CAR_API §3.2, §3.4).
 *
 * Reads band + frequency through [CarService] (AIDL `getRadioBand()` / `getRadioFreq()`) and
 * drives seek/band via `sendRadioKey()`. Every AIDL touch is already wrapped in CarService's
 * guard (returns null / no-op on RemoteException or SecurityException); on top of that this
 * card shows a **"Radio unavailable"** placeholder whenever we cannot read a frequency — so a
 * non-system app that can't reach the EventService degrades gracefully instead of crashing.
 *
 * v0.9 (Radio 2.0): band label + **presets** (save the current freq, tap a chip to recall via
 * `sendUserFreq()`), persisted in [RadioPresetsStore] (DataStore), rendered as a small
 * horizontally-scrolling station strip. Long-press a chip to delete it.
 *
 * ⚠ The `sendRadioKey()` opcode table and the `sendUserFreq()` freq units are GUESSED (see
 * [CarService] companion) — verify seek/band direction and preset recall on-device.
 *
 * v0.10: transport redesign. Band tag + frequency share one row, and seek/band are
 * full-width contained tonal buttons (44dp) instead of borderless IconButtons, sized to
 * always fit the card's fixed 180dp home-screen slot.
 */
@Composable
fun RadioCard(
    carService: CarService,
    modifier: Modifier = Modifier,
    presetsStore: RadioPresetsStore? = null, // v0.9; null keeps @Preview / no-store paths working
) {
    // Bump this to force an immediate re-poll after a tune/seek action.
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    /**
     * Drive one tuner control, then re-poll — the same helper [RadioScreen] uses.
     *
     * `sendRadioKey` / `sendUserFreq` are not `oneway` in the AIDL, so each is a *blocking*
     * binder round-trip to the vendor gateway. This card sits on Home, one tap away while
     * driving, and a held seek button ran that IPC on the main thread.
     */
    fun control(action: suspend () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { action() } }
            refresh++
        }
    }

    val info by produceState(initialValue = RadioInfo.UNKNOWN, refresh) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val freq = carService.getRadioFreq()
                    val band = carService.getRadioBand()
                    if (freq == null || freq <= 0) RadioInfo.UNKNOWN
                    else RadioInfo(available = true, band = band ?: 0, freq = freq)
                }.getOrDefault(RadioInfo.UNKNOWN)
            }
            delay(3_000)
        }
    }

    val presets by (presetsStore?.presets?.collectAsStateSafe(initial = emptyList())
        ?: remember { androidx.compose.runtime.mutableStateOf(emptyList<RadioPreset>()) })

    Card(
        modifier = modifier.carCard(accent = MaterialTheme.colorScheme.primary), // rotation wraps to pink
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        if (!info.available) {
            RadioUnavailable()
        } else {
            // The card lives in a fixed 180dp slot (HomeScreen right column). The v0.9 layout
            // over-filled it — header row + 40sp freq + presets + a row of borderless
            // IconButtons summed past the slot, so the transport row rendered squeezed and
            // near-invisible. This layout budgets the height explicitly: one compact
            // band+freq+star row, the preset strip, and a full-width transport row of
            // *contained* tonal buttons that can't be crowded out.
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BandChip(label = info.bandLabel())
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = info.freqLabel(),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // v0.9: save the current station as a preset.
                    if (presetsStore != null) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    presetsStore.add(RadioPreset(band = info.band, freq = info.freq))
                                }
                            },
                            modifier = Modifier.size(PRESET_STAR_DP.dp),
                        ) {
                            Icon(
                                Icons.Filled.StarBorder,
                                contentDescription = "Save preset",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }

                // v0.9: station strip (presets). Tap = recall, long-press = delete.
                if (presets.isNotEmpty()) {
                    StationStrip(
                        presets = presets,
                        activeBand = info.band,
                        activeFreq = info.freq,
                        // v0.4.7.1: land on the preset's band before tuning (RadioTuning) —
                        // an AM preset recalled while on FM mistuned.
                        onRecall = { p ->
                            control {
                                RadioTuning.recallPreset(
                                    readBand = { carService.getRadioBand() },
                                    toggleBand = { carService.radioBandToggle() },
                                    tune = { carService.sendUserFreq(it) },
                                    preset = p,
                                )
                            }
                        },
                        onDelete = { p -> scope.launch { presetsStore?.remove(p) } },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransportButton(
                        icon = Icons.Filled.FastRewind,
                        contentDescription = "Seek down",
                        onClick = { control { carService.radioSeekDown() } },
                        modifier = Modifier.weight(1.25f),
                    )
                    TransportButton(
                        icon = Icons.Filled.SwapHoriz,
                        contentDescription = "Band",
                        onClick = { control { carService.radioBandToggle() } },
                        modifier = Modifier.weight(1f),
                        emphasized = true,
                    )
                    TransportButton(
                        icon = Icons.Filled.FastForward,
                        contentDescription = "Seek up",
                        onClick = { control { carService.radioSeekUp() } },
                        modifier = Modifier.weight(1.25f),
                    )
                }
            }
        }
    }
}

/** Small filled band tag ("FM"/"AM") — carries the tuner identity now that the header row is gone. */
@Composable
private fun BandChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = carShape(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Radio,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Contained transport control: a full-height tonal surface instead of a borderless
 * IconButton, so the tap target is visible (and glove-sized) on the head unit.
 */
@Composable
private fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Surface(
        color = if (emphasized) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = carShape(12.dp),
        modifier = modifier.height(TRANSPORT_HEIGHT_DP.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationStrip(
    presets: List<RadioPreset>,
    activeBand: Int,
    activeFreq: Int,
    onRecall: (RadioPreset) -> Unit,
    onDelete: (RadioPreset) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { p ->
            // Band is part of the identity: AM 8750 must not light while tuned FM 87.5.
            val active = RadioTuning.presetMatches(p, activeBand, activeFreq)
            Surface(
                color = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                shape = carShape(50),
                modifier = Modifier.combinedClickable(
                    onClick = { onRecall(p) },
                    onLongClick = { onDelete(p) },
                ),
            ) {
                Text(
                    text = formatFreqLabel(p.band, p.freq),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RadioUnavailable() {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Radio,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Radio unavailable",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Format a raw `getRadioFreq()` value. Units aren't documented, so we cover the common
 * encodings: AM in kHz, FM either in 10 kHz units (8750 = 87.5 MHz) or kHz (95000).
 * ⚠ GUESSED encoding — verify on-device.
 */
internal fun formatFreqLabel(band: Int, freq: Int): String {
    if (freq <= 0) return "--"
    if (CarService.isAmBand(band)) {
        val khz = if (freq > 30000) freq / 1000 else freq
        return "$khz kHz"
    }
    val mhz = when {
        freq > 30000 -> freq / 1000.0      // kHz  -> MHz
        freq > 3000 -> freq / 100.0        // 10kHz-> MHz
        else -> freq / 10.0                // fallback
    }
    return "%.1f MHz".format(mhz)
}

/** Immutable snapshot of the tuner state for the card. */
private data class RadioInfo(
    val available: Boolean,
    val band: Int = 0,
    val freq: Int = 0,
) {
    fun bandLabel(): String = if (CarService.isAmBand(band)) "AM" else "FM"

    fun freqLabel(): String = formatFreqLabel(band, freq)

    companion object {
        val UNKNOWN = RadioInfo(available = false)
    }
}

/**
 * Deviation from §1.2's 76 dp: the card lives in a fixed 180 dp home slot, so the
 * transports get the tallest row that still fits under the freq row and preset strip,
 * and the star gets the 48 dp Material minimum inside the freq row.
 */
private const val TRANSPORT_HEIGHT_DP = 56
private const val PRESET_STAR_DP = 48
