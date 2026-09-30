package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.DspEq
import com.ripostelabs.carlauncher.carlib.McuSetup
import com.ripostelabs.carlauncher.carlib.McuSetupStore
import com.ripostelabs.carlauncher.carlib.BAL_FAD_HALF
import com.ripostelabs.carlauncher.carlib.ampToDisplay
import com.ripostelabs.carlauncher.carlib.displayToAmp

/**
 * Audio & EQ on Riposte OS 0.2: the same rows as [AudioSettingsScreen], read from and written
 * to [McuSetupStore] instead of the vendor gateway. Every control is one MCU frame
 * (`McuSetupProtocol`) and one persisted row; a panel key that changes the MCU first shows up
 * here through the store's report fold, so the controls never argue with the amp.
 */
@Composable
fun McuAudioSettingsScreen(
    store: McuSetupStore,
    onBack: () -> Unit,
) {
    val setup by store.setup.collectAsStateWithLifecycle()

    var editBands by remember { mutableStateOf(false) }

    SettingsScaffold(title = "Audio & EQ", onBack = onBack) {
        // The GT6 sound path: the DSP app's 48-band EQ, re-sent at every boot (RAV4-154).
        SettingsSection(title = "DSP equalizer") {
            PickerSetting(
                label = "Preset",
                current = setup.dspPreset,
                options = DSP_PRESETS,
                onSelect = { index -> DspEq.Preset.of(index)?.let(store::setDspPreset) },
            )
            DspCurve(setup.dspEq)
            ToggleSetting(
                label = "Edit bands",
                description = "48 bands, 16 Hz to 18 kHz, -10 to +10 dB",
                checked = editBands,
                onChange = { editBands = it },
            )
            if (editBands) {
                setup.dspEq.forEachIndexed { band, gain ->
                    SliderSetting(
                        label = "${DspEq.FREQUENCIES[band]} Hz",
                        value = gain,
                        range = DSP_GAIN_RANGE,
                        onChange = { store.setDspBand(band, it) },
                        format = ::toneLabel,
                    )
                }
            }
            PickerSetting(
                label = "Save curve to",
                current = DspEq.EDITED,
                options = DSP_SLOTS,
                onSelect = store::saveDspCustom,
            )
            ActionRow(
                label = "Reset sound",
                description = "Flat EQ and DSP loudness off; custom slots stay",
                onClick = store::resetDsp,
            )
        }

        SettingsSection(title = "Amp equalizer") {
            PickerSetting(
                label = "EQ preset",
                current = setup.eqMode,
                options = EQ_PRESETS,
                onSelect = store::setEqMode,
            )
            ToneSlider("Bass", setup.tone.bass) { store.setTone(setup.tone.copy(bass = it)) }
            ToneSlider("Mid", setup.tone.mid) { store.setTone(setup.tone.copy(mid = it)) }
            ToneSlider("Treble", setup.tone.treble) { store.setTone(setup.tone.copy(treble = it)) }
        }

        SettingsSection(title = "Balance & fader") {
            SliderSetting(
                label = "Balance",
                description = "Left ↔ right",
                value = ampToDisplay(setup.balance),
                range = BAL_FAD_RANGE,
                onChange = { store.setBalanceFader(displayToAmp(it), setup.fader) },
                format = ::balanceLabel,
            )
            SliderSetting(
                label = "Fader",
                description = "Front ↔ rear",
                value = ampToDisplay(setup.fader),
                range = BAL_FAD_RANGE,
                onChange = { store.setBalanceFader(setup.balance, displayToAmp(it)) },
                format = ::faderLabel,
            )
        }

        SettingsSection(title = "Enhancements") {
            VolumeSlider(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = "Subwoofer level",
                value = setup.subwoofer,
                range = SUBWOOFER_RANGE,
                onChange = store::setSubwoofer,
            )
            ToggleSetting(
                label = "Loudness",
                description = "Toggled through the MCU; the amp reports the result",
                checked = setup.loudness,
                onChange = { store.toggleLoudness() },
            )
            ToggleSetting(
                label = "DSP loudness",
                checked = setup.dspLoud,
                onChange = store::setDspLoud,
            )
            ToggleSetting(
                label = "Touch beep",
                checked = setup.keyBeep == McuSetup.Beep.ON,
                onChange = { store.setKeyBeep(if (it) McuSetup.Beep.ON else McuSetup.Beep.OFF) },
            )
            ActionRow(
                label = "Test beep",
                description = "Play a short tone through the audio path",
                onClick = store::beep,
            )
        }

        SettingsSection(title = "Source volume") {
            Text(
                text = "Per-source gain relative to the main volume, sent to the MCU at every boot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val g = setup.gains
            GainSlider(Icons.Filled.MusicNote, "Music", g.music) { store.setGains(g.copy(music = it)) }
            GainSlider(Icons.Filled.Bluetooth, "Bluetooth music", g.btMusic) { store.setGains(g.copy(btMusic = it)) }
            GainSlider(Icons.Filled.Call, "Bluetooth call", g.btCall) { store.setGains(g.copy(btCall = it)) }
            GainSlider(Icons.Filled.Radio, "Radio", g.radio) { store.setGains(g.copy(radio = it)) }
            GainSlider(Icons.Filled.Usb, "USB", g.usb) { store.setGains(g.copy(usb = it)) }
            GainSlider(Icons.Filled.Cable, "AUX", g.aux) { store.setGains(g.copy(aux = it)) }
            GainSlider(Icons.Filled.Movie, "DVD", g.dvd) { store.setGains(g.copy(dvd = it)) }
            GainSlider(Icons.Filled.Movie, "Video", g.movie) { store.setGains(g.copy(movie = it)) }
            GainSlider(Icons.Filled.Tv, "TV", g.tv) { store.setGains(g.copy(tv = it)) }
            GainSlider(Icons.AutoMirrored.Filled.VolumeUp, "Other", g.other) { store.setGains(g.copy(other = it)) }
            GainSlider(Icons.Filled.Navigation, "Navigation prompts", setup.navVolume, store::setNavVolume)
        }

        Text(
            text = "EQ preset names are inferred; the curves are the amp's. Ranges are the vendor's.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The 48-band curve as a line, flat in the middle, like the stock DSP app's chart. */
@Composable
private fun DspCurve(curve: List<Int>) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(CURVE_HEIGHT)
            .padding(vertical = 8.dp),
    ) {
        val mid = size.height / 2
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid))

        // One point per band, x evenly spread, y from +10 dB (top) to -10 dB (bottom).
        val dx = size.width / (curve.size - 1).coerceAtLeast(1)
        val scale = mid / DspEq.GAIN_MAX
        val path = Path()
        curve.forEachIndexed { band, gain ->
            val x = band * dx
            val y = mid - gain * scale
            if (band == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun ToneSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    SliderSetting(
        label = label,
        value = value - McuSetup.CENTRE,
        range = TONE_RANGE,
        onChange = { onChange((it + McuSetup.CENTRE).coerceIn(0, McuSetup.LEVEL_MAX)) },
        format = ::toneLabel,
    )
}

@Composable
private fun GainSlider(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    VolumeSlider(icon = icon, label = label, value = value, range = GAIN_RANGE, onChange = onChange)
}

/** The centred -7..7 the tone sliders show for the amp's 0..14. */
private val TONE_RANGE = -McuSetup.CENTRE..McuSetup.CENTRE

/** The centred -10..10 the balance sliders show for the DSP's 0..20 (see BalanceFaderMappingTest). */
private val BAL_FAD_RANGE = -BAL_FAD_HALF..BAL_FAD_HALF

/** Stock DSP presets by their saved index; "Edited" is the curve after a band move. */
private val DSP_PRESETS: List<Pair<Int, String>> =
    listOf(DspEq.EDITED to "Edited") + DspEq.Preset.values().map { it.index to it.label }

private val DSP_SLOTS: List<Pair<Int, String>> = List(DspEq.CUSTOM_SLOTS) { it to "Custom ${it + 1}" }

private val DSP_GAIN_RANGE = DspEq.GAIN_MIN..DspEq.GAIN_MAX

private val CURVE_HEIGHT = 96.dp

/** The vendor slider's span (`ItemSeekBarView`), the same the gateway path offered. */
private val SUBWOOFER_RANGE = 0..20
private val GAIN_RANGE = 0..40

/** Index order the vendor EQ picker used; names inferred, curves are the MCU's. */
private val EQ_PRESETS = listOf(
    0 to "Flat",
    1 to "Pop",
    2 to "Rock",
    3 to "Jazz",
    4 to "Classic",
    5 to "Vocal",
    6 to "Custom",
)

private fun balanceLabel(v: Int): String = when {
    v == 0 -> "Center"
    v < 0 -> "L${-v}"
    else -> "R$v"
}

private fun faderLabel(v: Int): String = when {
    v == 0 -> "Center"
    v < 0 -> "F${-v}"
    else -> "R$v"
}

private fun toneLabel(v: Int): String = when {
    v == 0 -> "0"
    v < 0 -> "$v"
    else -> "+$v"
}
