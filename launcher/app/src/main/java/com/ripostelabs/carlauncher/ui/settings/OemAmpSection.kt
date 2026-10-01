package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.OemAmp
import com.ripostelabs.carlauncher.carlib.OemAmpKey
import com.ripostelabs.carlauncher.carlib.OemAmpVolume
import com.ripostelabs.carlauncher.carlib.VolumeStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pause between two volume presses, so the box sees each one (stock sends one per tap). */
private const val VOLUME_PRESS_GAP_MS = 60L

/** How long after a write the page asks for a fresh 0xA6, as the car settings page does. */
private const val AMP_REPORT_AFTER_SET_MS = 400L

/**
 * RAV4-187: the car's factory amplifier (JBL), the stock "AMP" page, at the top of Audio.
 *
 *     open ──▶ 03 6A 05 01 A6 ──▶ box ──▶ 0xA6 ──▶ this section
 *     no 0xA6 ever ─────────────────────────────▶ nothing drawn
 *
 * A car without the amp never reports it, so the section stays empty there. Rows show what the
 * amp last reported; a write changes them only when the next report arrives. Volume moves in
 * presses (`02 AD 01 01|FF`) because stock never writes a level.
 */
@Composable
fun OemAmpSection(carService: CarService, carEvents: CarEvents) {
    val report by carEvents.oemAmp.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The stock page asks on every open.
    LaunchedEffect(Unit) { carService.requestOemAmp() }

    val amp = report ?: return

    // One write, then a fresh report so the rows show what the amp now holds.
    fun set(key: OemAmpKey, value: Int) {
        carService.setOemAmp(key, value)
        scope.launch {
            delay(AMP_REPORT_AFTER_SET_MS)
            carService.requestOemAmp()
        }
    }

    // A slider move of N becomes N presses, each from the level the previous one reached. One
    // run at a time: a tap during a run moves its target rather than starting a second run.
    val volume = remember { OemAmpVolume(amp.volume) }
    var pressing by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(amp.volume) { volume.onReport(amp.volume) }
    fun volumeTo(target: Int) {
        volume.aim(target)
        if (pressing?.isActive == true) {
            return
        }

        pressing = scope.launch {
            while (true) {
                val from = volume.at
                val step = volume.next() ?: break
                carService.stepOemAmpVolume(from, step)
                delay(VOLUME_PRESS_GAP_MS)
            }
            delay(AMP_REPORT_AFTER_SET_MS)
            carService.requestOemAmp()
        }
    }

    SettingsSection(title = "Original amplifier") {
        VolumeSlider(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            label = "Volume",
            value = amp.volume,
            range = OemAmpKey.VOLUME.range,
            onChange = ::volumeTo,
        )
        AmpSlider("Balance", OemAmpKey.BALANCE, amp.balance, OemAmp::balanceLabel, ::set)
        AmpSlider("Fade", OemAmpKey.FADE, amp.fade, OemAmp::fadeLabel, ::set)
        AmpSlider("Bass", OemAmpKey.BASS, amp.bass, OemAmp::toneLabel, ::set)
        AmpSlider("Mid", OemAmpKey.MID, amp.mid, OemAmp::toneLabel, ::set)
        AmpSlider("Treble", OemAmpKey.TREBLE, amp.treble, OemAmp::toneLabel, ::set)
        ToggleSetting(
            label = "ASL",
            description = "Raises the volume with road speed",
            checked = amp.asl,
            onChange = { set(OemAmpKey.ASL, if (it) 1 else 0) },
        )
        ToggleSetting(
            label = "Surround",
            checked = amp.surround,
            onChange = { set(OemAmpKey.SURROUND, if (it) 1 else 0) },
        )
        Text(
            text = "The car's own amplifier, set through the CAN box. Separate from the head unit's own EQ.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AmpSlider(
    label: String,
    key: OemAmpKey,
    value: Int,
    format: (Int) -> String,
    set: (OemAmpKey, Int) -> Unit,
) {
    SliderSetting(
        label = label,
        value = value,
        range = key.range,
        onChange = { set(key, it) },
        format = format,
    )
}
