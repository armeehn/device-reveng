package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.AccessoryCommand
import com.ripostelabs.carlauncher.carlib.AccessoryConfig
import com.ripostelabs.carlauncher.carlib.AccessoryKind
import com.ripostelabs.carlauncher.carlib.AccessoryState
import com.ripostelabs.carlauncher.carlib.Power
import com.ripostelabs.carlauncher.carlib.SequenceState
import com.ripostelabs.carlauncher.data.AccessoryRuntime
import com.ripostelabs.carlauncher.data.SettingsStore
import com.ripostelabs.carlauncher.ui.keyboard.CarTextField
import com.ripostelabs.carlauncher.ui.keyboard.CommitMode
import kotlinx.coroutines.launch
import java.io.File

/**
 * Lights, servos and sequences.
 *
 * Reads only from [AccessoryRuntime]; the engine keeps running when this page closes. Two rules
 * from the layers below show on the surface here:
 *
 *  - **Unknown is drawn as unknown.** There is no toggle: a switch drawn in the off position
 *    claims the thing is off, and a board that has not answered has not said that. Each
 *    accessory shows what the board last confirmed, and offers the two commands.
 *  - **The config is edited off the car.** A JSON blob on a touchscreen at 1920×720 is not a
 *    form anyone should fill in while parked in a lot. The base URL is the one field editable
 *    here; the rest is pasted in and every problem the parser found is listed, so a wrong paste
 *    is visible rather than silently partial.
 */
@Composable
fun AccessoriesScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit,
) {
    val settings by settingsStore.settings.collectAsStateWithLifecycle()
    val config by AccessoryRuntime.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var loadResult by remember { mutableStateOf<String?>(null) }
    val states by AccessoryRuntime.states.collectAsStateWithLifecycle()
    val running by AccessoryRuntime.sequence.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    SettingsScaffold(
        title = "Accessories",
        subtitle = "Lights, servos and sequences on the car network",
        onBack = onBack,
    ) {
        SettingsSection(title = "Board") {
            CarTextField(
                value = config.baseUrl,
                onValueChange = { url ->
                    // Re-serialise the parsed config with the new URL so a hand-edited blob is
                    // never rewritten by the launcher except for this one field.
                    settingsStore.setAccessoryConfig(config.copy(baseUrl = url.trim()).serialize())
                },
                label = "Base URL",
                placeholder = "http://accessories.car",
                commit = CommitMode.ON_DONE,
            )
            // The blob is edited off the car and arrives as a file, because DataStore is not
            // something adb can write and a JSON form on this screen is not something anyone
            // should fill in while parked. The app's external files dir is reachable by
            // `adb push` on the car and on the emulator alike, with no root.
            ActionRow(
                label = "Load config from file",
                description = "Reads ${CONFIG_FILE} from this app's external files directory.",
                onClick = {
                    loadResult = loadConfigFile(context)?.let { json ->
                        val parsed = AccessoryConfig.parse(json)
                        settingsStore.setAccessoryConfig(json)
                        "Loaded: ${parsed.accessories.size} accessories, ${parsed.sequences.size} sequences, " +
                            "${parsed.triggers.size} triggers" +
                            if (parsed.problems.isEmpty()) "" else ", ${parsed.problems.size} problems (listed below)"
                    } ?: "No ${CONFIG_FILE} found. Push one with adb: see ACCESSORY_BOARD.md."
                },
            )
            loadResult?.let { InfoRow(label = "Last load", value = it) }
            if (config.problems.isNotEmpty()) {
                config.problems.forEach { InfoRow(label = "Config", value = it) }
            }
            if (config.isEmpty) {
                Text(
                    text = "No accessories configured. Paste an AccessoryConfig JSON into the " +
                        "launcher settings (see can-integration/docs/ACCESSORY_BOARD.md).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        config.accessories.forEach { a ->
            val state = states[a.id]?.asOf(System.currentTimeMillis()) ?: AccessoryState()
            SettingsSection(title = a.name) {
                InfoRow(label = "State", value = describe(state))
                ActionRow(label = "Turn on", onClick = {
                    scope.launch { AccessoryRuntime.send(a.id, AccessoryCommand.SetPower(Power.ON)) }
                })
                ActionRow(label = "Turn off", onClick = {
                    scope.launch { AccessoryRuntime.send(a.id, AccessoryCommand.SetPower(Power.OFF)) }
                })
                if (a.kind == AccessoryKind.LEVEL) {
                    SliderSetting(
                        label = "Level",
                        value = state.level ?: 0,
                        range = 0..100,
                        onChange = { l -> scope.launch { AccessoryRuntime.send(a.id, AccessoryCommand.SetLevel(l)) } },
                        description = if (state.level == null) "Position unknown until the board answers" else null,
                        format = { "$it %" },
                    )
                }
            }
        }

        if (config.sequences.isNotEmpty()) {
            SettingsSection(title = "Sequences") {
                InfoRow(label = "Running", value = describe(running))
                config.sequences.forEach { seq ->
                    ActionRow(
                        label = seq.name,
                        description = "${seq.steps.size} steps",
                        onClick = { AccessoryRuntime.run(seq) },
                    )
                }
                ActionRow(label = "Stop", onClick = { AccessoryRuntime.cancel() }, enabled = running is SequenceState.Running)
            }
        }

        if (config.triggers.isNotEmpty()) {
            SettingsSection(title = "Triggers") {
                config.triggers.forEach { t ->
                    InfoRow(label = t.name, value = "${t.on.wire} → ${t.sequenceId}")
                }
            }
        }
    }
}

/** Where a pushed blob lands: `/sdcard/Android/data/<pkg>/files/accessory-config.json`. */
private const val CONFIG_FILE = "accessory-config.json"

/** The blob, or null when there is none. A file that exists but is junk still comes back, so the
 *  parser can list what is wrong with it rather than the screen saying "not found". */
private fun loadConfigFile(context: android.content.Context): String? {
    val file = File(context.getExternalFilesDir(null) ?: return null, CONFIG_FILE)
    return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
}

private fun describe(s: AccessoryState): String = when {
    !s.isKnown -> "unknown"
    s.power == Power.ON && s.level != null -> "on, ${s.level} %"
    s.power == Power.ON -> "on"
    s.power == Power.OFF -> "off"
    else -> "${s.level} %"
}

private fun describe(s: SequenceState): String = when (s) {
    is SequenceState.Idle -> "nothing"
    is SequenceState.Running -> "${s.sequence.name}, step ${s.nextStep + 1} of ${s.sequence.steps.size}"
    is SequenceState.Finished -> "${s.sequence.name} finished"
    is SequenceState.Aborted -> "${s.sequence.name} stopped: ${s.reason}"
}
