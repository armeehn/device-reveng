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
import com.ripostelabs.carlauncher.carlib.DspSound
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
    var editSpeakers by remember { mutableStateOf(false) }

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

        // The DSP app's Bass and Subwoofer tabs, `4F 16` and `4F 15`, re-sent at boot (RAV4-172).
        SettingsSection(title = "DSP subwoofer & bass") {
            DspSubRows(setup.dspSub, store::setDspSub)
            DspBassRows(setup.dspBass, store::setDspBass)
            ActionRow(
                label = "Default subwoofer & bass",
                description = "250 Hz, 0 dB, normal phase; bass boost off",
                onClick = {
                    store.setDspSub(DspSound.Sub())
                    store.setDspBass(DspSound.Bass())
                },
            )
        }

        // The DSP app's Sound field tab: time alignment `4F 12` and channel gain `4F 13` (RAV4-173).
        SettingsSection(title = "Listening position") {
            DspFieldRows(setup.dspField, editSpeakers, { editSpeakers = it }, store::setDspField)
        }

        // The DSP app's crossover (`4F 14`) and Surround tab (`4F 0F`), re-sent at boot (RAV4-190).
        SettingsSection(title = "Crossover & surround") {
            DspCrossoverRows(setup.dspCrossover, store::setDspCrossover)
            DspSurroundRows(setup.dspSurround, store::setDspSurround)
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
            ActionRow(
                label = "Restore defaults",
                description = "Every source and navigation prompts back to ${McuSetup.DEFAULT_GAIN}",
                onClick = store::restoreGains,
            )
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

/** Cut-off, gain around 0 dB, phase, amplifier and stock's unset on/off flag. */
@Composable
private fun DspSubRows(sub: DspSound.Sub, onChange: (DspSound.Sub) -> Unit) {
    SliderSetting(
        label = "Subwoofer cut-off",
        value = sub.freq,
        range = SUB_FREQ_RANGE,
        onChange = { onChange(sub.copy(freq = it)) },
        step = SUB_FREQ_STEP,
        format = { "$it Hz" },
    )
    SliderSetting(
        label = "Subwoofer gain",
        value = sub.gain - DspSound.SUB_GAIN_FLAT,
        range = SUB_GAIN_RANGE,
        onChange = { onChange(sub.copy(gain = it + DspSound.SUB_GAIN_FLAT)) },
        format = { "${toneLabel(it)} dB" },
    )
    ToggleSetting(
        label = "Reverse subwoofer phase",
        checked = sub.reversePhase,
        onChange = { onChange(sub.copy(reversePhase = it)) },
    )
    ToggleSetting(
        label = "Subwoofer amplifier",
        checked = sub.amplifier,
        onChange = { onChange(sub.copy(amplifier = it)) },
    )
    ToggleSetting(
        label = "Subwoofer on/off flag",
        description = "Stock never set it; the car shows which way is on",
        checked = sub.offOn,
        onChange = { onChange(sub.copy(offOn = it)) },
    )
}

/** Bass boost level and the stock picker's centre frequency. */
@Composable
private fun DspBassRows(bass: DspSound.Bass, onChange: (DspSound.Bass) -> Unit) {
    SliderSetting(
        label = "Bass boost",
        value = bass.level,
        range = BASS_LEVEL_RANGE,
        onChange = { onChange(bass.copy(level = it)) },
    )
    PickerSetting(
        label = "Bass boost frequency",
        current = bass.freq,
        options = BASS_FREQ_OPTIONS,
        onSelect = { onChange(bass.copy(freq = it)) },
    )
}

/**
 * A stock seat preset, then per speaker a distance (the delay a nearer speaker needs) and a
 * gain. A distance edit makes the seat Custom, as stock's dial did.
 */
@Composable
private fun DspFieldRows(
    field: DspSound.Field,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onChange: (DspSound.Field) -> Unit,
) {
    PickerSetting(
        label = "Seat",
        current = field.seat,
        options = SEAT_OPTIONS,
        onSelect = { onChange(field.withSeat(it)) },
    )
    ToggleSetting(
        label = "Edit per speaker",
        description = "Distance 0 to 272 cm and gain -80 to +15 dB",
        checked = editing,
        onChange = onEditing,
    )
    if (editing) {
        DspSound.Speaker.values().forEach { speaker ->
            SliderSetting(
                label = "${speaker.label} distance",
                value = field.delays[speaker.ordinal],
                range = DELAY_RANGE,
                onChange = { onChange(field.withDelay(speaker, it)) },
                step = DELAY_STEP,
                format = ::delayLabel,
            )
            SliderSetting(
                label = "${speaker.label} gain",
                value = field.gains[speaker.ordinal] - DspSound.GAIN_FLAT,
                range = FIELD_GAIN_RANGE,
                onChange = { onChange(field.withGain(speaker, it + DspSound.GAIN_FLAT)) },
                format = { "${toneLabel(it)} dB" },
            )
        }
    }
    ActionRow(
        label = "Default listening position",
        description = "All seats, no delay, every speaker at 0 dB",
        onClick = { onChange(DspSound.Field()) },
    )
}

/** Front and rear high- and low-pass, the two slopes, and a pass-everything default. */
@Composable
private fun DspCrossoverRows(crossover: DspSound.Crossover, onChange: (DspSound.Crossover) -> Unit) {
    HzSlider("Front high-pass", crossover.frontHp, HP_RANGE, HP_STEP) { onChange(crossover.copy(frontHp = it)) }
    HzSlider("Front low-pass", crossover.frontLp, LP_RANGE, LP_STEP) { onChange(crossover.copy(frontLp = it)) }
    HzSlider("Rear high-pass", crossover.rearHp, HP_RANGE, HP_STEP) { onChange(crossover.copy(rearHp = it)) }
    HzSlider("Rear low-pass", crossover.rearLp, LP_RANGE, LP_STEP) { onChange(crossover.copy(rearLp = it)) }
    PickerSetting(
        label = "High-pass slope",
        current = crossover.hpSlope,
        options = SLOPE_OPTIONS,
        onSelect = { onChange(crossover.copy(hpSlope = it)) },
    )
    PickerSetting(
        label = "Low-pass slope",
        current = crossover.lpSlope,
        options = SLOPE_OPTIONS,
        onSelect = { onChange(crossover.copy(lpSlope = it)) },
    )
    ActionRow(
        label = "Default crossover",
        description = "High-pass 20 Hz, low-pass 20 kHz: every speaker gets the full range",
        onClick = { onChange(DspSound.Crossover()) },
    )
}

@Composable
private fun HzSlider(label: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    SliderSetting(label = label, value = value, range = range, onChange = onChange, step = step, format = { "$it Hz" })
}

/** Surround on, the centre speaker, and stock's five modes. */
@Composable
private fun DspSurroundRows(surround: DspSound.Surround, onChange: (DspSound.Surround) -> Unit) {
    ToggleSetting(
        label = "Surround",
        checked = surround.on,
        onChange = { onChange(surround.copy(on = it)) },
    )
    ToggleSetting(
        label = "Centre speaker",
        checked = surround.centre,
        onChange = { onChange(surround.copy(centre = it)) },
    )
    PickerSetting(
        label = "Surround mode",
        current = surround.mode,
        options = SURROUND_OPTIONS,
        onSelect = { onChange(surround.copy(mode = it)) },
    )
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

private val SUB_FREQ_RANGE = DspSound.SUB_FREQ_MIN..DspSound.SUB_FREQ_MAX

/** 10 Hz notches, 20..250; stock's slider moved by 1 Hz, too fine to hit while parked. */
private const val SUB_FREQ_STEP = 10

/** The sub gain as -12..+12 dB around stock's 12. */
private val SUB_GAIN_RANGE = -DspSound.SUB_GAIN_FLAT..(DspSound.SUB_GAIN_MAX - DspSound.SUB_GAIN_FLAT)

private val BASS_LEVEL_RANGE = 0..DspSound.BASS_LEVEL_MAX

private val DELAY_RANGE = 0..DspSound.DELAY_MAX_CM

private val HP_RANGE = DspSound.HP_MIN..DspSound.HP_MAX
private val LP_RANGE = DspSound.LP_MIN..DspSound.LP_MAX

/** 10 Hz and 500 Hz notches: both ranges' ends sit on one (20..250, 3000..20000). */
private const val HP_STEP = 10
private const val LP_STEP = 500

private val SLOPE_OPTIONS: List<Pair<DspSound.Slope, String>> = DspSound.Slope.values().map { it to it.label }

private val SURROUND_OPTIONS: List<Pair<DspSound.SurroundMode, String>> =
    DspSound.SurroundMode.values().map { it to it.label }

/** 4 cm notches (about 0.12 ms); the stock seats' 136 and 272 cm both sit on one. */
private const val DELAY_STEP = 4

/** Stock's 0..95 gain as -80..+15 dB around its 80. */
private val FIELD_GAIN_RANGE = -DspSound.GAIN_FLAT..(DspSound.GAIN_MAX - DspSound.GAIN_FLAT)

/** Custom is listed so the row names it; picking it keeps the distances, as on stock. */
private val SEAT_OPTIONS: List<Pair<DspSound.Seat, String>> = DspSound.Seat.values().map { it to it.label }

private val BASS_FREQ_OPTIONS: List<Pair<Int, String>> = DspSound.BASS_FREQS.mapIndexed { i, label -> i to label }

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

/** Stock's "cm" and "ms" pair (FieldFragment_Two.java:397-398): 68 cm is 2.00 ms. */
private fun delayLabel(cm: Int): String = "$cm cm · ${"%.2f".format(cm.toFloat() / DspSound.CM_PER_MS)} ms"

private fun toneLabel(v: Int): String = when {
    v == 0 -> "0"
    v < 0 -> "$v"
    else -> "+$v"
}
