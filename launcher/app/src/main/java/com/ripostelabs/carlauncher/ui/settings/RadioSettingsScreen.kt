package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.ripostelabs.carlauncher.ui.theme.carShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.RadioPreset
import com.ripostelabs.carlauncher.data.RadioPresetsStore
import com.ripostelabs.carlauncher.ui.formatFreqLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.7 — Radio. Reskinned tuner settings: a live band/frequency + RDS readout from the vendor
 * control service (AIDL getters, CAR_API §3.2), plus preset management backed by
 * [RadioPresetsStore] (recall replays the raw frequency through `sendUserFreq`). AIDL exposes
 * only radio *getters* for RDS/TA/AF, so those are shown read-only; band toggle and seek are
 * live control actions.
 */
@Composable
fun RadioSettingsScreen(
    controller: CarSettingsController,
    carService: CarService,
    radioPresetsStore: RadioPresetsStore,
    onBack: () -> Unit,
) {
    val connected by carService.connected.collectAsStateWithLifecycle()
    val presets by radioPresetsStore.presets.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Live tuner echo; refreshed on entry and after control actions.
    var band by remember { mutableIntStateOf(0) }
    var freq by remember { mutableIntStateOf(0) }
    var rds by remember { mutableStateOf(false) }
    var ta by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    // Re-read the live tuner state off the main thread whenever we (re)connect or a control
    // action bumps refreshTick. Doing these blocking AIDL calls in the composition body — and
    // writing band/freq/rds/ta there — invalidated the composition and spun a main-thread IPC
    // recomposition loop while seeking (jank up to ANR).
    LaunchedEffect(connected, refreshTick) {
        if (connected) withContext(Dispatchers.IO) {
            band = carService.getRadioBand() ?: band
            freq = carService.getRadioFreq() ?: freq
            rds = carService.getRadioRds() ?: rds
            ta = carService.getRadioTa() ?: ta
        }
    }

    /**
     * Drive one tuner control, then re-read. The blocking *reads* above were moved off the main
     * thread after the incident noted there; the *writes* stayed inline. `sendRadioKey` /
     * `sendUserFreq` are not `oneway` either, so each was a blocking binder round-trip on the
     * main thread, and a held seek chip spammed them.
     */
    fun control(action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(action) }
            refreshTick++
        }
    }

    SettingsScaffold(
        title = "Radio",
        onBack = onBack,
        subtitle = if (connected) null else "Waiting for the vendor radio service…",
    ) {
        SettingsSection(title = "Tuner") {
            InfoRow(label = "Band", value = bandLabel(band, carService))
            InfoRow(label = "Frequency", value = formatFreqLabel(band, freq))
            InfoRow(label = "RDS", value = if (rds) "On" else "Off")
            InfoRow(label = "TA (traffic)", value = if (ta) "On" else "Off")
        }

        SettingsSection(title = "Controls") {
            ActionRow(
                label = "Toggle band (FM/AM)",
                onClick = { control { carService.radioBandToggle() } },
                enabled = connected,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallActionChip("Seek ◀", connected) { control { carService.radioSeekDown() } }
                SmallActionChip("Seek ▶", connected) { control { carService.radioSeekUp() } }
            }
        }

        SettingsSection(title = "Presets") {
            if (presets.isEmpty()) {
                Text(
                    text = "No presets yet. Tune a station, then save it below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                presets.forEach { preset ->
                    PresetRow(
                        label = formatFreqLabel(preset.band, preset.freq) +
                            "  ·  " + bandLabel(preset.band, carService),
                        onRecall = { control { carService.sendUserFreq(preset.freq, direct = true) } },
                        onDelete = { scope.launch { radioPresetsStore.remove(preset) } },
                        enabled = connected,
                    )
                }
            }
            Spacer(Modifier.padding(4.dp))
            ActionRow(
                label = "Save current station",
                description = "Store the tuner's current band + frequency",
                onClick = { scope.launch { radioPresetsStore.add(RadioPreset(band, freq)) } },
                enabled = connected,
            )
        }
    }
}

private fun bandLabel(band: Int, cs: CarService): String =
    if (CarService.isAmBand(band)) "AM" else "FM"

// Frequency formatting is shared with the home RadioCard ([formatFreqLabel]) so the same raw
// tuner value never renders differently on the two screens (a preset saved on one showed a
// 10x-off frequency on the other).

@Composable
private fun SmallActionChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(carShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetRow(
    label: String,
    onRecall: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onRecall)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "Delete preset",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(carShape(10.dp))
                .clickable(onClick = onDelete)
                .padding(6.dp),
        )
    }
}
