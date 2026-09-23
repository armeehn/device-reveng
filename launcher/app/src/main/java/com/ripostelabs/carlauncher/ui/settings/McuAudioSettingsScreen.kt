package com.ripostelabs.carlauncher.ui.settings

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.McuSetup
import com.ripostelabs.carlauncher.carlib.McuSetupStore
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

    SettingsScaffold(title = "Audio & EQ", onBack = onBack) {
        SettingsSection(title = "Equalizer") {
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
                range = DISPLAY_RANGE,
                onChange = { store.setBalanceFader(displayToAmp(it), setup.fader) },
                format = ::balanceLabel,
            )
            SliderSetting(
                label = "Fader",
                description = "Front ↔ rear",
                value = ampToDisplay(setup.fader),
                range = DISPLAY_RANGE,
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

@Composable
private fun ToneSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    SliderSetting(
        label = label,
        value = ampToDisplay(value),
        range = DISPLAY_RANGE,
        onChange = { onChange(displayToAmp(it)) },
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

/** The centred -7..7 the sliders show for the amp's 0..14 (see BalanceFaderMappingTest). */
private val DISPLAY_RANGE = -McuSetup.CENTRE..McuSetup.CENTRE

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
