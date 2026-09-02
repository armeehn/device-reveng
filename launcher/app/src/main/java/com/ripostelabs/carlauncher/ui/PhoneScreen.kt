package com.ripostelabs.carlauncher.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.HfpState
import com.ripostelabs.carlauncher.carlib.IntentSpec
import com.ripostelabs.carlauncher.carlib.VendorBt
import com.ripostelabs.carlauncher.carlib.VendorBtState
import com.ripostelabs.carlauncher.carlib.VendorCallLog
import com.ripostelabs.carlauncher.ui.theme.DISABLED_ALPHA
import com.ripostelabs.carlauncher.ui.theme.carShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RAV4-50 — the Phone screen, driven THROUGH the vendor bt app (`com.szchoiceway.btsuite`).
 *
 * btsuite owns the serial protocol to the BT module and cannot be replaced, so this screen never
 * talks to the module: it reads btsuite's `HBCP_EVT_*` broadcasts ([CarEvents.vendorBt]) and
 * sends btsuite's own control broadcasts ([VendorBt]). Whatever btsuite does not expose (phone
 * book, pairing) is one tap away on its own pages ([VendorBt.openPage]).
 *
 * ```
 *  ┌ Back  Phone ───────────────────────────────────────────────────────────────┐
 *  │ Pixel 9 · In call          02:15   │  Recent calls (CallListProvider)       │
 *  │ Alice  +1 604 …                    │   ↙ Alice        2026-09-02 14:05      │
 *  │ [ Answer ]        [ Hang up ]      │   ↗ +1 604 …     2026-09-01 09:12      │
 *  │ ┌ number ─────────────────── ⌫ ┐   │   ✕ Bob          2026-08-30 18:40      │
 *  │ │ 1 2 3 / 4 5 6 / 7 8 9 / * 0 #│   │                                        │
 *  │ └──────────────── [ Call ] ────┘   │  [Call log] [Contacts] [BT settings]   │
 *  └────────────────────────────────────┴────────────────────────────────────────┘
 * ```
 *
 * Parked-only (LAUNCHER_DESIGN §1.4): the dial pad and the call list are attention-heavy and are
 * withheld while moving. Answer / Hang up / the vendor-page buttons stay: a ringing phone is
 * exactly when a driver must be able to act with one forgiving press.
 *
 * ⚠ UNVERIFIED on the car: every control broadcast ([VendorBt]), whether the provider answers a
 * normal uid ([VendorCallLog]), and the speaking-time payload shape. The screen degrades to
 * "open the vendor page" wherever a source stays silent.
 */
@Composable
fun PhoneScreen(
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val vendor by carEvents.vendorBt.collectAsStateSafe(initial = VendorBtState())
    val feedback = LocalCarFeedback.current

    fun send(spec: IntentSpec) {
        feedback?.tap()
        spec.broadcast(context)
    }

    fun open(page: VendorBt.Page) {
        feedback?.tap()
        VendorBt.openPage(page).start(context)
    }

    // Seed the device name: btsuite only re-sends it on request (control key 8).
    LaunchedEffect(Unit) {
        VendorBt.requestDeviceName().broadcast(context)
    }

    // The provider is a blocking ContentResolver query; re-read when a call ends, since that is
    // when btsuite appends a row. null = not read yet, empty = unreadable or nothing there.
    val callLog by produceState<List<VendorCallLog.Entry>?>(initialValue = null, vendor.inCall) {
        value = withContext(Dispatchers.IO) { VendorCallLog.read(context) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PhoneHeader(onBack = onBack)

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CallStatus(vendor = vendor)
                CallButtons(
                    state = vendor.hfp,
                    onAnswer = { send(VendorBt.answer()) },
                    onHangUp = { send(VendorBt.hangUp()) },
                )
                ParkedOnly(
                    feature = "The dial pad",
                    modifier = Modifier.weight(1f),
                ) {
                    DialPad(
                        state = vendor.hfp,
                        onCall = { send(VendorBt.dial(it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ParkedOnly(
                    feature = "The call list",
                    modifier = Modifier.weight(1f),
                ) {
                    CallList(
                        entries = callLog,
                        canDial = vendor.hfp == HfpState.CONNECTED,
                        onDial = { send(VendorBt.dial(it)) },
                        onOpenVendorLog = { open(VendorBt.Page.CALL_RECORD) },
                        modifier = Modifier.weight(1f),
                    )
                }
                VendorPages(onOpen = ::open)
            }
        }
    }
}

@Composable
private fun PhoneHeader(onBack: () -> Unit) {
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
            text = "Phone",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Device, HFP state, and while a call is up the other party and the timer. */
@Composable
private fun CallStatus(vendor: VendorBtState) {
    val device = vendor.deviceName?.takeIf { it.isNotBlank() } ?: "No phone"
    val timer = vendor.speakingSec?.let(PhoneLogic::timer)
    val party = PhoneLogic.party(vendor)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$device · ${PhoneLogic.stateLabel(vendor.hfp)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (timer != null) {
                Text(
                    text = timer,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // The party line keeps its height while idle so the buttons below do not jump when
        // a call arrives.
        Text(
            text = party ?: " ",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
    }
}

/** Answer + Reject while ringing, Hang up during a call, nothing while idle. */
@Composable
private fun CallButtons(
    state: HfpState?,
    onAnswer: () -> Unit,
    onHangUp: () -> Unit,
) {
    val buttons = PhoneLogic.buttons(state)
    val hangUpLabel = if (state == HfpState.INCOMING_CALL) "Reject" else "Hang up"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CALL_BUTTON_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BigButton(
            label = "Answer",
            enabled = buttons.answer,
            color = MaterialTheme.colorScheme.primary,
            onClick = onAnswer,
            modifier = Modifier.weight(1f),
        )
        BigButton(
            label = hangUpLabel,
            enabled = buttons.hangUp,
            color = MaterialTheme.colorScheme.error,
            onClick = onHangUp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Number field, 3x4 keys, Call. The pad types even without a phone; only Call is gated. */
@Composable
private fun DialPad(
    state: HfpState?,
    onCall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var number by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(carShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = number.ifEmpty { "Enter number" },
                style = MaterialTheme.typography.headlineSmall,
                color = if (number.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = TRUNK_KEY.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(carShape(10.dp))
                    .clickable(onClick = withTapFeedback { number = PhoneLogic.append(number, TRUNK_KEY) })
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Backspace",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(44.dp)
                    .clip(carShape(10.dp))
                    .clickable(onClick = withTapFeedback { number = PhoneLogic.backspace(number) })
                    .padding(8.dp),
            )
        }

        for (rowKeys in DIAL_ROWS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (key in rowKeys) {
                    DialKey(
                        key = key,
                        onPress = { number = PhoneLogic.append(number, key) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        BigButton(
            label = "Call",
            enabled = PhoneLogic.canDial(state, number),
            color = MaterialTheme.colorScheme.primary,
            onClick = { onCall(number) },
            modifier = Modifier
                .fillMaxWidth()
                .height(CALL_BUTTON_HEIGHT_DP.dp),
        )
    }
}

@Composable
private fun DialKey(key: Char, onPress: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(carShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = withTapFeedback(onPress)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Recent calls from btsuite's provider; a tap dials. While the read is pending, or when it
 * yields nothing (unreadable OR empty — the provider cannot say which), the vendor's own
 * call-record page is offered instead.
 */
@Composable
private fun CallList(
    entries: List<VendorCallLog.Entry>?,
    canDial: Boolean,
    onDial: (String) -> Unit,
    onOpenVendorLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Recent calls",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (entries.isNullOrEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = if (entries == null) "Reading…" else "No call history readable here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                BigButton(
                    label = "Open vendor call log",
                    enabled = true,
                    color = MaterialTheme.colorScheme.secondary,
                    onClick = onOpenVendorLog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CALL_BUTTON_HEIGHT_DP.dp),
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(entries) { entry ->
                CallRow(entry = entry, canDial = canDial, onDial = onDial)
            }
        }
    }
}

@Composable
private fun CallRow(entry: VendorCallLog.Entry, canDial: Boolean, onDial: (String) -> Unit) {
    val (icon, tint) = when (entry.type) {
        VendorCallLog.CallType.MISSED -> Icons.AutoMirrored.Filled.CallMissed to MaterialTheme.colorScheme.error
        VendorCallLog.CallType.DIALED -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(carShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (canDial) Modifier.clickable(onClick = withTapFeedback { onDial(entry.number) }) else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            text = entry.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${entry.date} ${entry.time}".trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** The btsuite pages this screen does not replace. Never gated: they are the escape hatch. */
@Composable
private fun VendorPages(onOpen: (VendorBt.Page) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(VENDOR_BUTTON_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for ((label, page) in VENDOR_PAGES) {
            BigButton(
                label = label,
                enabled = true,
                color = MaterialTheme.colorScheme.secondary,
                onClick = { onOpen(page) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A car-scale filled button; disabled = dimmed and inert, never hidden, so the layout holds. */
@Composable
private fun BigButton(
    label: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = if (enabled) color else color.copy(alpha = DISABLED_ALPHA)
    Row(
        modifier = modifier
            .clip(carShape(14.dp))
            .background(fill)
            .then(if (enabled) Modifier.clickable(onClick = withTapFeedback(onClick)) else Modifier)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
    }
}

/** The pad as a phone lays it out; `+` sits in the number field, beside backspace. */
private val DIAL_ROWS = listOf(
    listOf('1', '2', '3'),
    listOf('4', '5', '6'),
    listOf('7', '8', '9'),
    listOf('*', '0', '#'),
)

private const val TRUNK_KEY = '+'

private val VENDOR_PAGES = listOf(
    "Call log" to VendorBt.Page.CALL_RECORD,
    "Contacts" to VendorBt.Page.PHONE_BOOK,
    "Bluetooth settings" to VendorBt.Page.SETTINGS,
)

/** Forgiving targets for a moving car: taller than the 48 dp minimum by a clear margin. */
private const val CALL_BUTTON_HEIGHT_DP = 72
private const val VENDOR_BUTTON_HEIGHT_DP = 56
