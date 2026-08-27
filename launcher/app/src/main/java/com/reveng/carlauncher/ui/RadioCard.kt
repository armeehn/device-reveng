package com.reveng.carlauncher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.data.RadioPreset
import com.reveng.carlauncher.data.RadioPresetsStore
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
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        if (!info.available) {
            RadioUnavailable()
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Radio,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = info.bandLabel(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // v0.9: save the current station as a preset.
                    if (presetsStore != null) {
                        IconButton(onClick = {
                            scope.launch {
                                presetsStore.add(RadioPreset(band = info.band, freq = info.freq))
                            }
                        }) {
                            Icon(
                                Icons.Filled.StarBorder,
                                contentDescription = "Save preset",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }

                Text(
                    text = info.freqLabel(),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // v0.9: station strip (presets). Tap = recall, long-press = delete.
                if (presets.isNotEmpty()) {
                    StationStrip(
                        presets = presets,
                        activeFreq = info.freq,
                        onRecall = { p ->
                            carService.sendUserFreq(p.freq); refresh++
                        },
                        onDelete = { p -> scope.launch { presetsStore?.remove(p) } },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        carService.radioSeekDown(); refresh++
                    }) {
                        Icon(
                            Icons.Filled.FastRewind,
                            contentDescription = "Seek down",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    FilledIconButton(onClick = {
                        carService.radioBandToggle(); refresh++
                    }) {
                        Icon(
                            Icons.Filled.SwapHoriz,
                            contentDescription = "Band",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    IconButton(onClick = {
                        carService.radioSeekUp(); refresh++
                    }) {
                        Icon(
                            Icons.Filled.FastForward,
                            contentDescription = "Seek up",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationStrip(
    presets: List<RadioPreset>,
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
            val active = p.freq == activeFreq
            Surface(
                color = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(50),
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
private fun formatFreqLabel(band: Int, freq: Int): String {
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
