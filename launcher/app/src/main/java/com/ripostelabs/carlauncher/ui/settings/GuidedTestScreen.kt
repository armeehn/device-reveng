package com.ripostelabs.carlauncher.ui.settings

import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.carlib.GuidedTest
import com.ripostelabs.carlauncher.carlib.GuidedTestJudge
import com.ripostelabs.carlauncher.carlib.SignalProbe
import com.ripostelabs.carlauncher.carlib.TestVerdict
import com.ripostelabs.carlauncher.service.CanCaptureService
import kotlinx.coroutines.delay

/**
 * Guided car tests — "operate this control now; did the car answer?"
 *
 * Attributing a signal here has always meant capturing, driving back, and trawling a log hours
 * later, by which point nobody could say exactly what was pressed or when. This puts the person
 * and the instrument in the same place: the screen says what to do, watches the bus either side
 * of it, and answers on the spot.
 *
 * The verdict logic is [GuidedTestJudge], in carlib, and is checked against archived captures
 * whose answers were already known. Nothing about the decision lives in this file.
 */
@Composable
fun GuidedTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val source = remember { CanCaptureService.shared(context) }

    var running by remember { mutableStateOf<GuidedTest?>(null) }
    var phase by remember { mutableStateOf<SignalProbe.Phase?>(null) }
    var secondsLeft by remember { mutableStateOf(0) }
    var verdict by remember { mutableStateOf<Pair<GuidedTest, TestVerdict>?>(null) }

    // The reader is shared with the capture service, so a test never opens the adapter itself.
    // Detaching on dispose matters: a probe left attached accumulates for the life of the process
    // and quietly poisons the next run with frames from a different state.
    DisposableEffect(Unit) {
        CanCaptureService.start(context)
        onDispose { source.attachProbe(null) }
    }

    LaunchedEffect(running) {
        val test = running ?: return@LaunchedEffect
        val probe = SignalProbe()
        source.attachProbe(probe)

        probe.phase(SignalProbe.Phase.BASELINE)
        phase = SignalProbe.Phase.BASELINE
        for (remaining in BASELINE_SECONDS downTo 1) {
            secondsLeft = remaining
            delay(ONE_SECOND_MS)
        }

        probe.phase(SignalProbe.Phase.ACTION)
        phase = SignalProbe.Phase.ACTION
        for (remaining in ACTION_SECONDS downTo 1) {
            secondsLeft = remaining
            delay(ONE_SECOND_MS)
        }

        source.attachProbe(null)
        val result = GuidedTestJudge.judge(test, probe)

        // The driver reads the screen and then leaves. Logging under the tag the desk-side
        // watcher already pulls means the finding travels on its own.
        Log.i(LOG_TAG, GuidedTestJudge.summary(test, result))
        verdict = test to result
        phase = null
        running = null
    }

    SettingsScaffold(
        title = "Guided car tests",
        subtitle = "One control at a time, with a verdict",
        onBack = onBack,
    ) {
        val active = running
        if (active != null) {
            SettingsSection(title = active.title) {
                RunningPhase(phase = phase, test = active, secondsLeft = secondsLeft)
            }
            return@SettingsScaffold
        }

        verdict?.let { (test, result) ->
            SettingsSection(title = test.title + " — result") {
                VerdictRows(result)
                ActionRow(
                    label = "Run it again",
                    description = "A second run holding a different state is what turns a " +
                        "reading into a finding.",
                    onClick = { verdict = null; running = test },
                )
            }
        }

        SettingsSection(title = "Tests") {
            Text(
                text = "Each test watches the bus with nothing happening, then while you hold " +
                    "one control. Hold it for the whole window: a brief press looks like a " +
                    "0.3 second event, and telling those apart is the point.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))

            GuidedTest.CATALOGUE.forEach { test ->
                ActionRow(
                    label = test.title + if (test.expected == null) "  (search)" else "",
                    description = test.note ?: test.actionPrompt,
                    onClick = { verdict = null; running = test },
                )
            }
        }
    }
}

/** What to do right now, and for how long. */
@Composable
private fun RunningPhase(phase: SignalProbe.Phase?, test: GuidedTest, secondsLeft: Int) {
    val prompt = when (phase) {
        SignalProbe.Phase.BASELINE -> test.baselinePrompt
        SignalProbe.Phase.ACTION -> test.actionPrompt
        null -> "Finishing"
    }
    val heading = when (phase) {
        SignalProbe.Phase.BASELINE -> "Do nothing yet"
        SignalProbe.Phase.ACTION -> "NOW"
        null -> ""
    }

    InfoRow(label = heading, value = secondsLeft.toString() + " s")
    Text(
        text = prompt,
        style = MaterialTheme.typography.titleMedium,
        color = if (phase == SignalProbe.Phase.ACTION) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

/**
 * The answer.
 *
 * A failed prediction shows what DID move, because that list is the next hypothesis and is the
 * more useful half of the result. A search shows the same list and never claims a pass.
 */
@Composable
private fun VerdictRows(verdict: TestVerdict) {
    when (verdict) {
        is TestVerdict.Responded -> {
            InfoRow(label = "Answered", value = verdict.label)
            InfoRow(
                label = "The car",
                value = if (verdict.set) "set it while you held" else "cleared it while you held",
            )
            InfoRow(label = "Movement", value = percent(verdict.delta))
            verdict.alsoMoved.forEach { InfoRow(label = "Also moved", value = describe(it)) }
        }

        is TestVerdict.NoResponse -> {
            InfoRow(label = "No answer from", value = verdict.label)
            InfoRow(
                label = "It moved",
                value = verdict.observedDelta?.let { percent(it) } ?: "never seen",
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "What did move, biggest first. This is the next thing to test, not noise.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            verdict.topMovers.forEach { InfoRow(label = "Moved", value = describe(it)) }
            verdict.disjointBytes.forEach { InfoRow(label = "Disjoint byte", value = describe(it)) }
        }

        is TestVerdict.Observed -> {
            if (verdict.topMovers.isEmpty() && verdict.disjointBytes.isEmpty()) {
                Text(
                    text = "Nothing on this bus moved. That is a result: whatever you operated " +
                        "is not reported here, and a repeat run makes it a finding.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return
            }
            verdict.topMovers.forEach { InfoRow(label = "Moved", value = describe(it)) }
            verdict.disjointBytes.forEach { InfoRow(label = "Disjoint byte", value = describe(it)) }
        }

        is TestVerdict.NotEnoughData -> Text(
            text = "Not enough bus traffic to answer: " + verdict.baselineFrames +
                " frames before, " + verdict.actionFrames + " during. Check the adapter is " +
                "plugged in and the car is in READY, then run it again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun percent(fraction: Double): String = "%.0f%%".format(kotlin.math.abs(fraction) * 100)

private fun describe(c: SignalProbe.Candidate): String =
    "0x%03X byte %d bit 0x%02X  %.0f%% to %.0f%%".format(
        c.id, c.byteIndex, c.bitMask, c.baselineFraction * 100, c.actionFraction * 100,
    )

private fun describe(d: SignalProbe.DisjointByte): String =
    "0x%03X byte %d  %s then %s".format(
        d.id, d.byteIndex,
        d.baselineValues.sorted().joinToString(",") { "0x%02X".format(it) },
        d.actionValues.sorted().joinToString(",") { "0x%02X".format(it) },
    )

/** The tag the capture service already uses, so one pull collects both. */
private const val LOG_TAG = "Canable"

private const val BASELINE_SECONDS = 12
private const val ACTION_SECONDS = 20
private const val ONE_SECOND_MS = 1_000L
