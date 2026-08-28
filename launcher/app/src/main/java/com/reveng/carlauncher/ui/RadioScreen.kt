package com.reveng.carlauncher.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.data.RadioPreset
import com.reveng.carlauncher.data.RadioPresetsStore
import com.reveng.carlauncher.ui.theme.carShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.6 — the full-screen tuner (LAUNCHER_DESIGN §3.4).
 *
 * [RadioCard] is the Home glance widget; this is the screen for actually working the tuner.
 *
 * **What the roadmap asked for versus what the firmware has.** §3.4 planned "seek/scan" and an
 * "RDS text" line. Neither exists:
 *
 *  * **No scan.** The 144-method AIDL has `sendRadioKey`, `sendUserFreq` and a pile of *status*
 *    getters. `getRadioAMSState` / `getRadioAPSState` report whether an auto-store or
 *    auto-preset-scan is running, but nothing *starts* one. Seek is all we can drive.
 *  * **No radio text.** There is no PS (station name) or RT (radio text) getter anywhere.
 *    `getRadioPTYName` returns the programme *genre* ("Pop Music"), not a station. So this
 *    screen shows the indicator set that genuinely exists — RDS / TA / AF / TP / stereo plus
 *    the PTY genre — instead of an empty scroller pretending to be RDS text.
 *
 * Reading the tuner is polled, not pushed: `setRadioCallback` exists but its `ICallbackfn`
 * signature was never recovered, so registering it would be a guess. Blocking AIDL reads stay
 * off the composition body — doing them inline once spun a main-thread IPC recomposition loop
 * while seeking (see the incident note in `RadioSettingsScreen`).
 */
@Composable
fun RadioScreen(
    carService: CarService,
    presetsStore: RadioPresetsStore?,
    onBack: () -> Unit,
    vendorPresets: List<String> = emptyList(),
) {
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val tuner by produceState(initialValue = TunerState.UNKNOWN, refresh) {
        while (true) {
            value = withContext(Dispatchers.IO) { readTuner(carService) }
            delay(POLL_INTERVAL_MS)
        }
    }

    val presets by (presetsStore?.presets?.collectAsStateSafe(initial = emptyList())
        ?: remember { androidx.compose.runtime.mutableStateOf(emptyList<RadioPreset>()) })

    Column(modifier = Modifier.fillMaxSize()) {
        RadioHeader(tuner = tuner, onBack = onBack)

        if (!tuner.available) {
            RadioUnavailableNotice()
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            FrequencyReadout(tuner = tuner)

            IndicatorRow(tuner = tuner)

            PresetRow(
                presets = presets.take(PRESET_SLOTS),
                activeFreq = tuner.freq,
                onRecall = { preset ->
                    carService.sendUserFreq(preset.freq)
                    refresh++
                },
                onDelete = { preset ->
                    scope.launch { presetsStore?.remove(preset) }
                },
                onSaveCurrent = {
                    scope.launch {
                        presetsStore?.add(RadioPreset(band = tuner.band, freq = tuner.freq))
                    }
                },
                canSave = presets.size < PRESET_SLOTS,
            )

            TunerControls(
                onSeekDown = { carService.radioSeekDown(); refresh++ },
                onBand = { carService.radioBandToggle(); refresh++ },
                onSeekUp = { carService.radioSeekUp(); refresh++ },
            )

            VendorPresetLine(values = vendorPresets)
        }
    }
}

@Composable
private fun RadioHeader(tuner: TunerState, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .size(48.dp)
                .clip(carShape(12.dp))
                .clickable(onClick = withTapFeedback(onBack))
                .padding(8.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = "Radio",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = tuner.bandLabel(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .clip(carShape(14.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

/** §3.4's 48 sp frequency — set explicitly because it is a legibility requirement, not a style. */
@Composable
private fun FrequencyReadout(tuner: TunerState) {
    Text(
        text = formatFreqLabel(tuner.band, tuner.freq),
        fontSize = FREQUENCY_SP.sp,
        lineHeight = (FREQUENCY_SP * 1.1f).sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
    )
}

/**
 * The tuner status flags that actually exist. Every one is read-only: the AIDL exposes no radio
 * setter beyond seek/band/tune, so these report the tuner rather than controlling it.
 */
@Composable
private fun IndicatorRow(tuner: TunerState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Indicator(label = "RDS", on = tuner.rds)
        Indicator(label = "TA", on = tuner.ta)
        Indicator(label = "AF", on = tuner.af)
        Indicator(label = "TP", on = tuner.tp)
        Indicator(label = "ST", on = tuner.stereo)

        if (!tuner.ptyName.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = tuner.ptyName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A flag is drawn dim when off and greyed when unknown — an unreadable tuner and a tuner
 * reporting "off" are different facts, and collapsing them would make a failed AIDL call look
 * like a deliberate setting.
 */
@Composable
private fun Indicator(label: String, on: Boolean?) {
    val bg = when (on) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.surfaceVariant
        null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = UNKNOWN_ALPHA)
    }
    val fg = when (on) {
        true -> MaterialTheme.colorScheme.onPrimary
        false -> MaterialTheme.colorScheme.onSurfaceVariant
        null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UNKNOWN_ALPHA)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = fg,
        modifier = Modifier
            .clip(carShape(10.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Six preset slots (§3.4). Tap a filled slot to recall it, tap its ✕ to delete, tap an empty
 * slot to store the current station.
 *
 * Delete is an explicit target rather than the card's long-press: this screen is where presets
 * are managed, and a long-press is undiscoverable and easy to trigger by accident on a bumpy
 * road.
 */
@Composable
private fun PresetRow(
    presets: List<RadioPreset>,
    activeFreq: Int,
    onRecall: (RadioPreset) -> Unit,
    onDelete: (RadioPreset) -> Unit,
    onSaveCurrent: () -> Unit,
    canSave: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        presets.forEach { preset ->
            PresetSlot(
                preset = preset,
                active = preset.freq == activeFreq,
                onRecall = { onRecall(preset) },
                onDelete = { onDelete(preset) },
                modifier = Modifier.weight(1f),
            )
        }

        repeat(PRESET_SLOTS - presets.size) {
            EmptyPresetSlot(
                enabled = canSave,
                onSave = onSaveCurrent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PresetSlot(
    preset: RadioPreset,
    active: Boolean,
    onRecall: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .height(PRESET_HEIGHT_DP.dp)
            .clip(carShape(14.dp))
            .background(bg)
            .clickable(onClick = withTapFeedback(onRecall))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatFreqLabel(preset.band, preset.freq),
            style = MaterialTheme.typography.titleMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Delete preset",
            tint = fg,
            modifier = Modifier
                .size(36.dp)
                .clip(carShape(8.dp))
                .clickable(onClick = withTapFeedback(onDelete))
                .padding(6.dp),
        )
    }
}

@Composable
private fun EmptyPresetSlot(enabled: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(PRESET_HEIGHT_DP.dp)
            .clip(carShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = EMPTY_SLOT_ALPHA))
            .clickable(enabled = enabled, onClick = withTapFeedback(onSave)),
    ) {
        Text(
            text = "+ Save",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TunerControls(onSeekDown: () -> Unit, onBand: () -> Unit, onSeekUp: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TunerButton(Icons.Filled.FastRewind, "Seek down", onSeekDown)
        TunerButton(Icons.Filled.SwapHoriz, "Change band", onBand, filled = true)
        TunerButton(Icons.Filled.FastForward, "Seek up", onSeekUp)
    }
}

@Composable
private fun TunerButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    filled: Boolean = false,
) {
    val bg = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(TRANSPORT_TARGET_DP.dp)
            .clip(carShape(24.dp))
            .background(bg)
            .clickable(onClick = withTapFeedback(onClick)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = fg,
            modifier = Modifier.size(48.dp),
        )
    }
}

/**
 * The vendor's own presets, shown raw and read-only.
 *
 * The roadmap wanted our presets to *sync* with `Rdo_MyFavorite0..5`. We deliberately do not
 * write them: the encoding of those SysVar values is documented nowhere in the decompile, and
 * writing a guessed format would corrupt the presets in the vendor radio app with no way to
 * tell what was there before. Displaying the raw strings is the useful half — it is exactly the
 * capture needed to work the format out on-device, after which two-way sync becomes safe.
 */
@Composable
private fun VendorPresetLine(values: List<String>) {
    if (values.isEmpty()) {
        return
    }
    Text(
        text = "Vendor presets (raw, read-only): " + values.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RadioUnavailableNotice() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Radio unavailable.\nThe vendor EventService did not report a frequency.",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** One poll's worth of tuner state. Nullable flags mean "the AIDL call did not answer". */
private data class TunerState(
    val available: Boolean,
    val band: Int,
    val freq: Int,
    val rds: Boolean? = null,
    val ta: Boolean? = null,
    val af: Boolean? = null,
    val tp: Boolean? = null,
    val stereo: Boolean? = null,
    val ptyName: String? = null,
) {
    fun bandLabel(): String = if (CarService.isAmBand(band)) "AM" else "FM"

    companion object {
        val UNKNOWN = TunerState(available = false, band = 0, freq = 0)
    }
}

/** Blocking — call from [Dispatchers.IO]. */
private fun readTuner(carService: CarService): TunerState {
    return runCatching {
        val freq = carService.getRadioFreq()
        if (freq == null || freq <= 0) {
            return@runCatching TunerState.UNKNOWN
        }
        TunerState(
            available = true,
            band = carService.getRadioBand() ?: 0,
            freq = freq,
            rds = carService.getRadioRds(),
            ta = carService.getRadioTa(),
            af = carService.getRadioAf(),
            tp = carService.getRadioTp(),
            stereo = carService.getRadioStereo(),
            ptyName = carService.getRadioPtyName(),
        )
    }.getOrDefault(TunerState.UNKNOWN)
}

/** LAUNCHER_DESIGN §3.4: six presets, 48 sp frequency, 96 dp targets. */
private const val PRESET_SLOTS = 6
private const val FREQUENCY_SP = 48f
private const val TRANSPORT_TARGET_DP = 96
private const val PRESET_HEIGHT_DP = 64

/** Matches RadioCard's cadence: the tuner changes slowly and each read is an IPC round-trip. */
private const val POLL_INTERVAL_MS = 3_000L

private const val UNKNOWN_ALPHA = 0.35f
private const val EMPTY_SLOT_ALPHA = 0.5f
