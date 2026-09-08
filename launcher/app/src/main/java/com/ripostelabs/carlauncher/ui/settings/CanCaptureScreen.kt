package com.ripostelabs.carlauncher.ui.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.ui.theme.JetBrainsMono
import com.ripostelabs.carlauncher.carlib.CanFrame
import com.ripostelabs.carlauncher.carlib.CanableSource
import com.ripostelabs.carlauncher.carlib.CanableStatus
import com.ripostelabs.carlauncher.carlib.CanSignal
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.HiworldCanDecoder
import com.ripostelabs.carlauncher.carlib.RadarCapture
import com.ripostelabs.carlauncher.ui.collectAsStateSafe
import com.ripostelabs.carlauncher.ui.theme.carShape

/**
 * v0.4.3 — the raw CAN bulk frame (`CAN_BASIC_EVT` / `MCU_CAR_CAN_INFO`), for on-device capture.
 *
 * Decoding this frame is the standing upgrade to the GPS-only speed source (README "Known TODOs";
 * it is available instantly at power-on and indoors, where GPS is not). Two things are unknown from
 * a desk and only a car settles them: **which action actually arrives** (the fully-qualified
 * strings are GUESSED at the vendor's `EventUtils.*` prefix) and **which extra carries the
 * payload** (never quoted in the decompile). So this ships the instrument, mirroring the v2.8 radar
 * capture: every extra of each frame is listed by name, and any `byte[]` payload runs through the
 * same [RadarCapture] min/max/change accumulator so the byte that tracks speed reveals itself when
 * the car is driven.
 *
 * The capture to perform:
 *   1. Open this screen with the engine running (frames should arrive at power-on, no reverse
 *      needed — unlike radar).
 *   2. Note which **action** and **extra key** appear. If nothing appears at all, the action
 *      strings are wrong and need re-deriving from the decompile.
 *   3. Press Reset while stationary, then drive slowly. Exactly the byte(s) encoding speed climb
 *      with the car; a gear/handbrake flag jumps once. `min`/`max`/`changes` separate them.
 *   4. "Write to logcat" records it for `adb logcat -s CanCapture` on the drive back.
 *
 * Not gated parked-only: like the radar capture it is a stationary-then-slow diagnostic, and the
 * parked gate rests on a GPS fix this frame exists to replace.
 */
@Composable
fun CanCaptureScreen(
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val frame by carEvents.canRaw.collectAsStateSafe(initial = null)

    // The USB adapter is a second, independent path to the same bus: the vendor broadcast above is
    // whatever the MCU chose to forward, this is the wire. Started only while the screen is open.
    val context = LocalContext.current
    val canable = remember { CanableSource.create(context) }
    val usb by canable.status.collectAsStateSafe(initial = CanableStatus.Idle)
    DisposableEffect(canable) {
        canable.start()
        onDispose { canable.stop() }
    }

    var capture by remember { mutableStateOf(RadarCapture()) }
    var lastFrame by remember { mutableStateOf<CanFrame?>(null) }
    // Latest decoded value per label, from HiworldCanDecoder. Each opcode arrives in its own
    // frame, so we merge rather than replace — the table shows the freshest reading of each.
    var decoded by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(frame) {
        val f = frame ?: return@LaunchedEffect
        lastFrame = f
        val bytes = f.bytes ?: return@LaunchedEffect
        capture = capture.accept(bytes)
        HiworldCanDecoder.decodeFrame(bytes)?.let { sig ->
            val rows = decodedRows(sig)
            if (rows.isNotEmpty()) decoded = decoded + rows
        }
    }

    SettingsScaffold(
        title = "CAN frame capture",
        subtitle = "MCU_MSG_CAN_ALL_INFO (raw MCU frames) / MCU_CAR_CAN_INFO (3-byte speed, rpmH, rpmL)",
        onBack = onBack,
    ) {
        SettingsSection(title = "Broadcast") {
            val f = lastFrame
            if (f == null) {
                Text(
                    text = "No CAN frame received yet. The action strings are GUESSED at the " +
                        "EventUtils.* prefix — if nothing arrives on a running car, they are wrong " +
                        "and must be re-derived from the decompile (EvtModel.java).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                InfoRow(label = "Action", value = f.action.substringAfterLast('.'))
                InfoRow(label = "Extras", value = "${f.extras.size}")
                InfoRow(
                    label = "Payload",
                    value = f.bytes?.let { "byte[${it.size}]" } ?: "none found",
                )
            }
        }

        SettingsSection(title = "Decoded (HiworldCanDecoder)") {
            if (decoded.isEmpty()) {
                Text(
                    text = "No frame decoded yet. Confirmed signals (RPM, hybrid, SWC, doors, " +
                        "steering, range) appear as their frames arrive on a running car.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                decoded.forEach { (label, value) -> InfoRow(label = label, value = value) }
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Speed is a CANDIDATE, not calibrated: the 2026-08-29 drive proved road " +
                        "speed is NOT the 0x32 field. The real speed is 0x17 (accurate ~0.1 km/h but " +
                        "low-rate, scale on 2 points) and 0x13 p[0:1] (live ~10 Hz, scale unconfirmed) " +
                        "— a steady-cruise capture is needed to finish calibration. Gear: only REVERSE " +
                        "is in the digest (0x71 bit 0x02); P/N/D are not transmitted, so 0x1A gear bytes " +
                        "are raw only. None of these feed the motion gate or dashboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SettingsSection(title = "Extras (every key, undecoded)") {
            val extras = lastFrame?.extras
            if (extras.isNullOrEmpty()) {
                Text(
                    text = "Waiting for a frame. Each extra is shown by its real key name so the " +
                        "one carrying the payload can be identified on-device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                extras.forEach { (key, value) -> ExtraRow(key = key, value = value) }
            }
        }

        SettingsSection(title = "Payload bytes") {
            InfoRow(label = "Frames since reset", value = "${capture.frames}")
            InfoRow(
                label = "Payload length",
                value = if (capture.frames == 0) "—" else "${capture.payloadSize} bytes",
            )
            ActionRow(
                label = "Reset baseline",
                description = "Clears min/max/changes. Press while stationary before driving.",
                onClick = { capture = RadarCapture() },
            )
            ActionRow(
                label = "Write table to logcat",
                description = "Tag $LOG_TAG — for `adb logcat -s $LOG_TAG` alongside the drive.",
                onClick = { dumpToLog(lastFrame, capture) },
                enabled = capture.frames > 0,
            )
            Spacer(Modifier.size(8.dp))
            if (capture.frames == 0) {
                Text(
                    text = "No byte[] payload accumulated yet — either no frame has arrived or the " +
                        "payload rides an extra that isn't a byte array (check the Extras list).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ByteTable(capture = capture)
            }
        }

        SettingsSection(title = "USB CAN adapter (CANable)") {
            CanableRows(status = usb, onGrant = { canable.requestAccess() })
        }
    }
}

/**
 * The adapter's own state, kept apart from the vendor broadcast above.
 *
 * Three readings, because "nothing is happening" has three different causes and one number cannot
 * separate them:
 *   - **Firmware** answered → the USB path works, whatever CAN is doing.
 *   - **Frames** climbing → CAN-H/CAN-L are on a live bus.
 *   - **Rejected** climbing → the adapter refused a command, so the channel likely never opened.
 */
@Composable
private fun CanableRows(status: CanableStatus, onGrant: () -> Unit) {
    when (status) {
        is CanableStatus.Idle -> Text(
            text = "Not started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is CanableStatus.NoAdapter -> Text(
            text = "No CANable found on either USB port. This kernel has no CDC-ACM driver, so the " +
                "adapter never appears as /dev/ttyACM* — it is claimed directly over USB instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is CanableStatus.NoPermission -> ActionRow(
            label = "Grant USB access",
            description = "Adapter found. Android needs permission before it can be claimed.",
            onClick = onGrant,
        )

        is CanableStatus.Failed -> Text(
            text = "Adapter found but not usable: ${status.reason}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        is CanableStatus.Running -> {
            InfoRow(label = "Firmware", value = status.version ?: "no answer yet")
            InfoRow(label = "Frames", value = "${status.frames}")
            InfoRow(label = "Rate", value = "${status.ratePerSec} /s")
            InfoRow(label = "Rejected", value = "${status.rejected}")
            InfoRow(label = "Unreadable lines", value = "${status.unparsed}")
            InfoRow(label = "Distinct IDs", value = "${status.distinctIds}")
            InfoRow(label = "Captured", value = "${status.captureBytes / 1024} kB")

            Spacer(Modifier.size(8.dp))
            if (status.frames == 0L) {
                Text(
                    text = if (status.version == null) {
                        "Channel open, adapter silent. Nothing has been read back at all yet."
                    } else {
                        "Adapter is talking, but no CAN frames. CAN-H/CAN-L are probably not on the " +
                            "HiWorld tap (pin 11 H, pin 12 L, pin 1 GND), or the car is off."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                status.ids.forEach { (id, count) ->
                    InfoRow(label = "0x%03X".format(id), value = "$count")
                }
            }
        }
    }
}

@Composable
internal fun ExtraRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1.4f)
                .clip(carShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/** One header line + one line per offset in logcat, to record a drive for reading back at a desk. */
private fun dumpToLog(frame: CanFrame?, capture: RadarCapture) {
    Log.i(LOG_TAG, "action=${frame?.action} extras=${frame?.extras?.keys} frames=${capture.frames} payload=${capture.payloadSize}")
    capture.bytes.forEach { stat ->
        Log.i(
            LOG_TAG,
            "[%02d] cur=%3d min=%3d max=%3d changes=%d".format(
                stat.index, stat.value, stat.min, stat.max, stat.changes,
            ),
        )
    }
}

/**
 * Flatten one decoded [CanSignal] into label→value rows for the Decoded section. Confirmed
 * signals are shown plainly; speed and gear are labelled RAW because the 0x32 speed field and
 * the 0x1A gear codes are not yet calibrated (see the caveat rendered under the section).
 */
private fun decodedRows(sig: CanSignal): Map<String, String> = when (sig) {
    is CanSignal.VehicleInfo -> buildMap {
        put("RPM", sig.rpm.toString())
        put("0x32 p[4:5] (NOT speed)", sig.speedRaw.toString())
        put("Coolant", sig.coolantC?.let { "$it °C" } ?: "—")
    }
    is CanSignal.SpeedCandidate -> mapOf(
        "Speed (${sig.source} candidate, ~0.1 km/h)" to "%.1f km/h (raw %d)".format(sig.kmh, sig.raw),
    )
    is CanSignal.Hybrid -> buildMap {
        put("Hybrid battery", "${sig.batteryLevel}/15")
        put("Energy flow", "0x%02X".format(sig.energyFlowRaw))
    }
    is CanSignal.BasicStatus -> buildMap {
        val swc = if (sig.swcAction == HiworldCanDecoder.SwcAction.UNKNOWN) {
            "id ${sig.swcButtonId}"
        } else {
            "${sig.swcAction.name.lowercase().replace('_', ' ')} (id ${sig.swcButtonId})"
        }
        put("SWC button", swc + if (sig.swcPressed) " — pressed" else "")
        // Every opening the vendor byte carries, named. Listing only the ones that are open
        // keeps the row short in the common case and makes a stuck sensor obvious.
        val openings = buildList {
            if (sig.doorFrontLeftOpen) add("driver")
            if (sig.doorFrontRightOpen) add("passenger")
            if (sig.doorRearLeftOpen) add("rear L")
            if (sig.doorRearRightOpen) add("rear R")
            if (sig.tailgateOpen) add("tailgate")
            if (sig.hoodOpen) add("bonnet")
        }
        put("Doors", if (openings.isEmpty()) "all shut" else openings.joinToString(", ") + " open")
        put("Steering", "%.1f°".format(sig.steerAngleDeg))
    }
    is CanSignal.Climate -> buildMap {
        fun temp(v: Double?) = v?.let { "%.1f".format(it) + if (sig.tempUnitCelsius) "\u00B0C" else "\u00B0F" } ?: "LO/HI"
        fun level(v: Int) = if (v == 0) "off" else "$v"
        // Flagged in the UI, not just in a comment: this layout has never been checked against
        // a real vehicle and was reported as not tracking the physical controls.
        put("Climate", (if (sig.on) "on" else "off") + "  (unverified decode)")
        put("A/C", listOfNotNull(
            if (sig.acOn) "on" else null,
            if (sig.acMax) "max" else null,
            if (sig.auto) "auto" else null,
            if (sig.eco) "eco" else null,
            if (sig.recirculate) "recirc" else null,
        ).joinToString(", ").ifEmpty { "off" })
        put("Fan", "step ${sig.fanStep}" + if (sig.rearFanStep > 0) " (rear ${sig.rearFanStep})" else "")
        put("Set temp L/R", "${temp(sig.leftTempC)} / ${temp(sig.rightTempC)}" + if (sig.dual) " (dual)" else "")
        put("Seat heat L/R", "${level(sig.seatHeatLeft)} / ${level(sig.seatHeatRight)}")
        put("Seat vent L/R", "${level(sig.seatCoolLeft)} / ${level(sig.seatCoolRight)}")
    }
    is CanSignal.SysEvent -> buildMap {
        // reverseRaw is the bit WITHOUT the vendor's ACC gate, so it is labelled as raw rather
        // than presented as "in reverse" — the two can legitimately disagree with the ignition off.
        put("Reverse (raw bit)", if (sig.reverseRaw) "engaged" else "not engaged")
        put("Media present", listOfNotNull(
            if (sig.discPresent) "disc" else null,
            if (sig.usbPresent) "USB" else null,
        ).joinToString(", ").ifEmpty { "none" })
        put("Sys byte", "0x%02X".format(sig.raw))
    }
    is CanSignal.SideCamera -> buildMap {
        val sides = listOfNotNull(
            if (sig.left) "left" else null,
            if (sig.right) "right" else null,
        )
        put("Side camera", if (sides.isEmpty()) "none requested" else sides.joinToString(" + "))
        // These cameras are indicator-triggered on this car, and the indicators are NOT on the raw
        // CAN bus at all, so this row is the only turn-signal state available anywhere.
        put("Turn signal (via camera)", if (sides.isEmpty()) "off" else sides.joinToString(" + "))
        if (sig.leftForced) put("Left camera", "forced on (bit3)")
    }
    is CanSignal.Tpms -> buildMap {
        fun kpa(v: Int?) = v?.let { "$it kPa" } ?: "—"
        put("TPMS FL/FR", "${kpa(sig.frontLeftKpa)} / ${kpa(sig.frontRightKpa)}")
        put("TPMS RL/RR", "${kpa(sig.rearLeftKpa)} / ${kpa(sig.rearRightKpa)}")
    }
    is CanSignal.ParkingRadar -> buildMap {
        fun cm(l: List<Int?>) = l.joinToString(" ") { it?.toString() ?: "–" }
        put("Radar rear", cm(sig.rearCm))
        put("Radar front", cm(sig.frontCm))
    }
    is CanSignal.TripInfo -> buildMap {
        put("Range to empty", sig.rangeToEmptyKm?.let { "$it km" } ?: "—")
        put("Speed (0x13 live candidate, raw)", sig.speedCandidateRaw.toString())
    }
    is CanSignal.RpmGearMirror -> mapOf(
        "Gear" to sig.gear.name,
        "Gear raw (0x1A b1,b5)" to "0x%02X,0x%02X".format(sig.gearRawB1, sig.gearRawB5),
    )
    is CanSignal.Version -> mapOf("CANBOX firmware" to sig.text)
    // Exhaustive on purpose: no `else`. A new CanSignal type must fail to compile here until
    // someone decides how to show it. Both SideCamera and SysEvent were decoded and shipped while
    // rendering nothing at all, because an `else` branch swallowed them without a word.
    is CanSignal.Unknown -> emptyMap()
}

private const val LOG_TAG = "CanCapture"
