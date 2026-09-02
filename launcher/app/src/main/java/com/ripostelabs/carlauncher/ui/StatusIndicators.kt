package com.ripostelabs.carlauncher.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Call // RAV4-52 CarPlay chip
import androidx.compose.material.icons.filled.NetworkWifi1Bar
import androidx.compose.material.icons.filled.NetworkWifi2Bar
import androidx.compose.material.icons.filled.NetworkWifi3Bar
import androidx.compose.material.icons.filled.SignalWifi0Bar
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Smartphone // RAV4-52 CarPlay chip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.ripostelabs.carlauncher.ui.theme.DISABLED_ALPHA
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.carlib.CarEvents // v0.4.9 vendor BT status
import com.ripostelabs.carlauncher.carlib.CarPlayState // RAV4-52
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.VendorBtState // v0.4.9
import com.ripostelabs.carlauncher.data.BrightnessController
import com.ripostelabs.carlauncher.ui.theme.carShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v3.1 — always-visible status chips for the top bar: Wi-Fi, Bluetooth, volume, brightness
 * (ROADMAP "Status you can see"). Display-only per LAUNCHER_DESIGN §2.1 — the strip is glance
 * data and the SWC focus ring does not visit it; tapping the group opens the existing Quick
 * Controls panel, so status lives in the bar and control sits one tap below it.
 *
 * Every chip degrades by disappearing (or greying) rather than freezing: no Bluetooth adapter
 * -> no chip, EventService unbound -> no volume chip, unreadable backlight -> no brightness chip.
 * A chip that lies is worse than no chip.
 *
 * Update paths, cheapest first:
 *   Wi-Fi       push  — ConnectivityManager.NetworkCallback (signal via onCapabilitiesChanged)
 *   Bluetooth   push  — adapter state + connection-state broadcasts
 *   brightness  push  — ContentObserver on Settings.System.SCREEN_BRIGHTNESS
 *   volume      push  — the gateway's MCU_MSG_MAIL_VOL broadcast ([CarEvents.volume]); the
 *                       AIDL getter is polled only as a fallback, see [rememberVolumeStatus].
 *                       Binder death is still a push, so the chip goes the moment the service
 *                       dies rather than a poll period later.
 */

private const val VOLUME_POLL_MS = 5_000L

/**
 * Gateway status line that *may* announce a main-volume change (`showVolState`,
 * `EventService.java:13899`). It rides the `ZXW_MESSAGE_TO_ICCOMMUNICATION` broadcast as a
 * String, not the `addMessageListener(ICommunication)` channel we are registered on — nothing in
 * the gateway ever calls those listeners — so this match is inert today and only nudges a re-read
 * of the confirmed getter. The number on the chip is never parsed out of it.
 */
private const val VOLUME_MESSAGE_PREFIX = "SYSTEM_VOLUME"

private const val WIFI_BARS = 4

// WifiInfo.INVALID_RSSI is @hide; the framework uses -127 as the sentinel.
private const val INVALID_RSSI = -127

/**
 * Stable identities for the four chips, pinned by the instrumentation suite
 * (app/src/androidTest). ROADMAP holds the strip as a stability invariant from v3.1 to v4.0:
 * a redesign may restyle a chip freely, but must not silently drop one. Tags are asserted on,
 * so renaming one is the same breaking change as deleting the chip — do both in one commit.
 */
internal object StatusIndicatorTags {
    const val GROUP = "statusIndicators"
    const val WIFI = "statusIndicator.wifi"
    const val BLUETOOTH = "statusIndicator.bluetooth"
    const val VOLUME = "statusIndicator.volume"
    const val BRIGHTNESS = "statusIndicator.brightness"
    /** RAV4-52: the phone-projection chip. Not in the v3.1 invariant set; present only while connected. */
    const val CARPLAY = "statusIndicator.carplay"
}

@Composable
fun StatusIndicators(
    carService: CarService?,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
    onOpen: (() -> Unit)? = null,
    // v0.4.9: vendor HBCP_EVT_* BT status (null keeps previews / older callers unchanged).
    carEvents: CarEvents? = null,
) {
    val context = LocalContext.current.applicationContext

    StatusIndicatorsRow(
        wifi = rememberWifiStatus(context),
        bt = rememberBluetoothStatus(context, carEvents),
        volume = rememberVolumeStatus(carService, carEvents, refreshKey),
        brightnessPercent = rememberBrightnessPercent(context, refreshKey),
        modifier = modifier,
        onOpen = onOpen,
        carPlay = rememberCarPlayStatus(carEvents),
    )
}

/**
 * The strip's rendering, with every source already resolved. Split out from [StatusIndicators]
 * so a test can drive all four sources — including the ones an emulator has no hardware for
 * (no vendor EventService, so no volume; no car, so no MCU backlight) — without pretending a
 * source is there. Each parameter carries its own "absent" value, and absent means *no chip*:
 * that is the ROADMAP rule the tests exist to hold. A chip that lies is worse than no chip.
 */
@Composable
internal fun StatusIndicatorsRow(
    wifi: WifiStatus,
    bt: BtStatus,
    volume: VolumeStatus,
    brightnessPercent: Int?,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    // RAV4-52: the projected phone. The default is "no session", i.e. no chip.
    carPlay: CarPlayState = CarPlayState(),
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .testTag(StatusIndicatorTags.GROUP)
            .clip(carShape(8.dp))
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Wi-Fi is the one source the framework always answers for, radio off included, so this
        // chip is unconditional — "off" is a state, not a missing source.
        WifiChip(wifi)
        if (bt.present) {
            BluetoothChip(bt)
        }
        if (volume.available) {
            VolumeChip(volume)
        }
        // RAV4-52: shown only while Zlink reports a live session; silence = no chip.
        if (carPlay.connected) {
            StatusChip(
                icon = if (carPlay.inCall) Icons.Filled.Call else Icons.Filled.Smartphone,
                description = "Phone projection",
                tint = MaterialTheme.colorScheme.primary,
                text = carPlayChipText(carPlay),
                tag = StatusIndicatorTags.CARPLAY,
            )
        }
        // null = brightness unreadable (no WRITE_SETTINGS, no MCU backlight): the chip goes,
        // rather than parking on a stale percentage.
        if (brightnessPercent != null) {
            StatusChip(
                icon = Icons.Filled.BrightnessMedium,
                description = "Brightness $brightnessPercent%",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                text = "$brightnessPercent%",
                tag = StatusIndicatorTags.BRIGHTNESS,
            )
        }
    }
}

// ---- Wi-Fi -----------------------------------------------------------------

internal data class WifiStatus(
    val enabled: Boolean,
    val connected: Boolean,
    val validated: Boolean,
    val bars: Int,
)

@Composable
private fun WifiChip(wifi: WifiStatus) {
    val (icon, description) = when {
        !wifi.enabled -> Icons.Filled.SignalWifiOff to "Wi-Fi off"
        !wifi.connected -> Icons.Filled.SignalWifi0Bar to "Wi-Fi disconnected"
        else -> wifiBarsIcon(wifi.bars) to "Wi-Fi ${wifi.bars}/$WIFI_BARS bars" +
            if (wifi.validated) "" else " (no internet)"
    }
    val tint = when {
        !wifi.enabled || !wifi.connected ->
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
        // Connected but unvalidated (captive portal / no internet): distinct, not alarming.
        !wifi.validated -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    StatusChip(icon = icon, description = description, tint = tint, tag = StatusIndicatorTags.WIFI)
}

private fun wifiBarsIcon(bars: Int): ImageVector = when (bars) {
    0 -> Icons.Filled.SignalWifi0Bar
    1 -> Icons.Filled.NetworkWifi1Bar
    2 -> Icons.Filled.NetworkWifi2Bar
    3 -> Icons.Filled.NetworkWifi3Bar
    else -> Icons.Filled.SignalWifi4Bar
}

@Composable
private fun rememberWifiStatus(context: Context): WifiStatus {
    val wm = remember { context.getSystemService(WifiManager::class.java) }
    var status by remember {
        mutableStateOf(
            WifiStatus(
                enabled = runCatching { wm?.isWifiEnabled == true }.getOrDefault(false),
                connected = false,
                validated = false,
                bars = 0,
            ),
        )
    }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)

        // Signal + validated ride the capabilities callback; it fires on RSSI change.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // transportInfo is a WifiInfo on a TRANSPORT_WIFI network; RSSI is not
                // location-redacted (only SSID/BSSID are), so no location permission needed.
                val rssi = (caps.transportInfo as? WifiInfo)?.rssi
                status = WifiStatus(
                    enabled = true,
                    connected = true,
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    bars = wifiBars(wm, rssi),
                )
            }

            override fun onLost(network: Network) {
                status = WifiStatus(
                    enabled = runCatching { wm?.isWifiEnabled == true }.getOrDefault(false),
                    connected = false,
                    validated = false,
                    bars = 0,
                )
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm?.registerNetworkCallback(request, callback) }

        // The callback never fires while the radio is simply off — track that separately.
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                val enabled = state == WifiManager.WIFI_STATE_ENABLED
                status = if (enabled) {
                    status.copy(enabled = true)
                } else {
                    WifiStatus(enabled = false, connected = false, validated = false, bars = 0)
                }
            }
        }
        runCatching {
            context.registerReceiver(
                stateReceiver,
                IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
                // System broadcast: delivered to NOT_EXPORTED receivers identically,
                // and NOT_EXPORTED refuses same-named spoofs from other apps.
                Context.RECEIVER_NOT_EXPORTED,
            )
        }

        onDispose {
            runCatching { cm?.unregisterNetworkCallback(callback) }
            runCatching { context.unregisterReceiver(stateReceiver) }
        }
    }
    return status
}

private fun wifiBars(wm: WifiManager?, rssi: Int?): Int {
    if (wm == null || rssi == null || rssi <= INVALID_RSSI) {
        return 0
    }
    return runCatching {
        val max = wm.maxSignalLevel
        if (max <= 0) 0 else (wm.calculateSignalLevel(rssi) * WIFI_BARS + max / 2) / max
    }.getOrDefault(0).coerceIn(0, WIFI_BARS)
}

// ---- Bluetooth -------------------------------------------------------------

internal data class BtStatus(
    val present: Boolean,
    val on: Boolean,
    val connectedCount: Int,
)

@Composable
private fun BluetoothChip(bt: BtStatus) {
    val (icon, description, tint) = when {
        !bt.on -> Triple(
            Icons.Filled.BluetoothDisabled,
            "Bluetooth off",
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
        )
        bt.connectedCount > 0 -> Triple(
            Icons.Filled.BluetoothConnected,
            "Bluetooth: ${bt.connectedCount} connected",
            MaterialTheme.colorScheme.primary,
        )
        else -> Triple(
            Icons.Filled.Bluetooth,
            "Bluetooth on, nothing connected",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    StatusChip(
        icon = icon,
        description = description,
        tint = tint,
        text = if (bt.connectedCount > 1) "${bt.connectedCount}" else null,
        tag = StatusIndicatorTags.BLUETOOTH,
    )
}

/**
 * v0.4.9 — how long after the last HBCP_EVT_* broadcast the vendor verdict is still trusted.
 * The events are edge-triggered, so "arriving" can only mean "arrived recently"; past this,
 * the chip falls back to the Android source, which is exactly the pre-v0.4.9 behaviour.
 */
private const val VENDOR_BT_FRESH_MS = 10 * 60_000L

/**
 * v0.4.9 — fold the vendor bt module's verdict into the Android-sourced [BtStatus].
 *
 * Only ever ADDITIVE, and only for the presence/connected booleans: the head unit's phone
 * Bluetooth runs through the vendor `btsuite` module, which the Android `BluetoothManager` can
 * be blind to — so a vendor "connected" with a silent Android stack is the case this exists
 * for. The two are separate stacks (the module owns the phone, Android may hold something
 * else), so a vendor "disconnected" never overrides an Android-confirmed connection, and an
 * absent/stale vendor state (the caller's freshness guard) changes nothing at all.
 */
internal fun applyVendorBt(android: BtStatus, vendor: VendorBtState): BtStatus {
    var merged = android
    if (vendor.powered == true && !merged.on) {
        merged = merged.copy(present = true, on = true)
    }
    if (vendor.connected == true && merged.connectedCount == 0) {
        merged = merged.copy(present = true, on = true, connectedCount = 1)
    }
    return merged
}

@Composable
private fun rememberBluetoothStatus(context: Context, carEvents: CarEvents? = null): BtStatus {
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }

    // getState/isEnabled need no permission on API 33; the *count* paths (profile probe,
    // connection-state broadcasts) want BLUETOOTH_CONNECT, a runtime permission on 31+.
    // Everything is runCatching-guarded: ungranted -> the chip degrades to on/off, no count.
    // On the rooted unit: pm grant com.ripostelabs.carlauncher android.permission.BLUETOOTH_CONNECT
    var on by remember { mutableStateOf(runCatching { adapter?.isEnabled == true }.getOrDefault(false)) }
    var connected by remember { mutableStateOf(setOf<String>()) }

    DisposableEffect(Unit) {
        if (adapter == null) {
            onDispose {}
        } else {
            // Seed "something is connected" from the audio profiles; the receiver then keeps
            // an exact per-device set as connect/disconnect events arrive.
            runCatching {
                val audioProfiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
                if (audioProfiles.any {
                        adapter.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED
                    }
                ) {
                    connected = setOf("seed")
                }
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            val state = intent.getIntExtra(
                                BluetoothAdapter.EXTRA_STATE,
                                BluetoothAdapter.ERROR,
                            )
                            on = state == BluetoothAdapter.STATE_ON
                            if (!on) {
                                connected = emptySet()
                            }
                        }

                        BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                            val state = intent.getIntExtra(
                                BluetoothAdapter.EXTRA_CONNECTION_STATE,
                                BluetoothAdapter.ERROR,
                            )
                            val address = intent
                                .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                ?.address ?: return
                            connected = when (state) {
                                BluetoothAdapter.STATE_CONNECTED -> connected - "seed" + address
                                BluetoothAdapter.STATE_DISCONNECTED -> connected - "seed" - address
                                else -> connected
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            }
            // System broadcasts — NOT_EXPORTED receives them identically (see Wi-Fi receiver).
            runCatching { context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) }

            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    // v0.4.9: vendor HBCP_EVT_* verdict, used only while its events are actually arriving.
    // The freshness latch opens on each event and times itself back shut, so a unit that
    // never broadcasts (emulator, non-vendor build) never leaves the Android source.
    val vendor by (carEvents?.vendorBt?.collectAsStateSafe(initial = VendorBtState())
        ?: remember { mutableStateOf(VendorBtState()) })
    var vendorFresh by remember { mutableStateOf(false) }
    LaunchedEffect(vendor) {
        if (vendor.lastEventMs == 0L) {
            vendorFresh = false
            return@LaunchedEffect
        }
        vendorFresh = true
        delay(VENDOR_BT_FRESH_MS)
        vendorFresh = false
    }

    val android = BtStatus(present = adapter != null, on = on, connectedCount = connected.size)
    return if (vendorFresh) applyVendorBt(android, vendor) else android
}

// ---- Volume ----------------------------------------------------------------

internal data class VolumeStatus(
    val available: Boolean,
    val level: Int,
    val muted: Boolean,
)

/** The chip's "say nothing" state: unbound, dead binder, or a read that failed. */
private val VOLUME_UNAVAILABLE = VolumeStatus(available = false, level = 0, muted = false)

/**
 * True for a gateway status line that may carry a main-volume change. Prefix-only on purpose:
 * the payload format after the marker is not documented anywhere in the decompile, so nothing
 * downstream reads it — see [VOLUME_MESSAGE_PREFIX].
 */
internal fun isVolumeMessage(message: String?): Boolean =
    message?.trimStart()?.startsWith(VOLUME_MESSAGE_PREFIX) == true

@Composable
private fun VolumeChip(volume: VolumeStatus) {
    if (volume.muted) {
        StatusChip(
            icon = Icons.AutoMirrored.Filled.VolumeOff,
            description = "Muted",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
            tag = StatusIndicatorTags.VOLUME,
        )
    } else {
        StatusChip(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            description = "Volume ${volume.level}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            text = "${volume.level}",
            tag = StatusIndicatorTags.VOLUME,
        )
    }
}

/**
 * The chip's value is pushed: the gateway broadcasts `MCU_MSG_MAIL_VOL` on every MCU volume or
 * mute report (`EventService.java:3105-3125`), decoded by [CarEvents.volume]. The AIDL poll below
 * is kept only as a fallback for the first frames and for a `carEvents`-less caller.
 *
 * Why the poll was the only path until now, so the next reader does not repeat the search:
 *
 *  - `ICallbackfn` IS recovered (`{ notifyEvt(what, arg1, arg2, byte[], String); checkIsActive() }`,
 *    `ICallbackfn.java:28-30`), but no registrar delivers volume: `setRadioCallback` (29) is
 *    tuner state, `setCurModeCallback` (30) / `setCamAuxCallback` (66) are the mode/key stream,
 *    and the CanA5/CanA6 callbacks are never fired. Registering as a mode callback is also a
 *    mode CLAIM, which is how PR #29 bricked the top bar.
 *  - The audio surface itself: `getMainVolval` (103), `IsMuteOn` (104), `sendVolState` (77),
 *    `sendMuteState` (8), `getSndSWVol` (58). All getters and setters, no subscription.
 *  - There is no LocalSocket: `LocalSocketServer` is never instantiated, and the
 *    `addMessageListener(ICommunication)` listeners are stored but never called. The
 *    `SYSTEM_VOLUME:` text line rides the `ZXW_MESSAGE_TO_ICCOMMUNICATION` broadcast instead.
 *
 * The two things that would make either path lie are still handled as pushes: a dead binder
 * and an unbind both take the chip away immediately.
 */
@Composable
private fun rememberVolumeStatus(
    carService: CarService?,
    carEvents: CarEvents?,
    refreshKey: Int,
): VolumeStatus {
    var status by remember { mutableStateOf(VOLUME_UNAVAILABLE) }
    // Once a push has landed the poll stands down: re-reading the getter every few seconds
    // would only race the broadcast with a value it already delivered.
    var pushed by remember { mutableStateOf(false) }

    LaunchedEffect(carEvents) {
        carEvents?.volume?.collect { reading ->
            if (reading != null) {
                pushed = true
                status = VolumeStatus(available = true, level = reading.level, muted = reading.muted)
            }
        }
    }

    LaunchedEffect(carService, refreshKey) {
        if (carService == null) {
            status = VOLUME_UNAVAILABLE
            return@LaunchedEffect
        }

        // Both getters are read-only and run off the main thread. CarService guards each call, so
        // a RemoteException or a DeadObjectException from a binder that died mid-read arrives here
        // as null — and null removes the chip instead of freezing the last number on screen.
        suspend fun read(): VolumeStatus = withContext(Dispatchers.IO) {
            val level = runCatching { carService.getMainVolume() }.getOrNull()
                ?: return@withContext VOLUME_UNAVAILABLE
            val muted = runCatching { carService.isMuteOn() }.getOrDefault(false)
            VolumeStatus(available = true, level = level, muted = muted)
        }

        // The one real push available: onServiceDisconnected flips `connected`, so a dropped
        // binder clears the chip at once rather than up to [VOLUME_POLL_MS] later.
        launch {
            carService.connected.collect { live ->
                if (!live) {
                    status = VOLUME_UNAVAILABLE
                }
            }
        }

        // Opportunistic only: the message listener is already registered on bind, so watching it
        // costs no extra call into the vendor service. If a volume line ever does arrive, re-read
        // the confirmed getter; if it never does, this is inert and the poll is unchanged.
        launch {
            carService.messages.collect { message ->
                if (isVolumeMessage(message)) {
                    status = read()
                }
            }
        }

        // Fallback poll: only until the first MCU_MSG_MAIL_VOL arrives.
        while (true) {
            if (!pushed) {
                status = read()
            }
            delay(VOLUME_POLL_MS)
        }
    }
    return status
}

// ---- Brightness ------------------------------------------------------------

@Composable
private fun rememberBrightnessPercent(context: Context, refreshKey: Int): Int? {
    // null = nothing readable behind the chip, so the chip disappears rather than freezing.
    // It covers the first frames too, before the read below has landed.
    var percent by remember { mutableStateOf<Int?>(null) }

    // currentPercent is a Settings.System query. It used to run in the composable body — on the
    // strip the 1 Hz clock recomposes — and again on the observer's main-looper callback. Both
    // now only *ask* for a read; the query itself runs on Dispatchers.IO. Conflated because a
    // brightness drag fires the observer in bursts and only the last reading matters.
    val reads = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(Unit) {
        for (request in reads) {
            percent = withContext(Dispatchers.IO) { BrightnessController.currentPercent(context) }
        }
    }

    LaunchedEffect(refreshKey) {
        reads.trySend(Unit)
    }

    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reads.trySend(Unit)
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                observer,
            )
        }
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    return percent
}

// ---- CarPlay (RAV4-52) -----------------------------------------------------

private const val CARPLAY_NAME = "CarPlay"
private const val CALL_WORD = "call"

/** `phoneMode` prefix → driver-facing name (the values `ZlinkManage.java:232-248` stores). */
private val PROJECTION_NAMES = linkedMapOf(
    "carplay_" to CARPLAY_NAME,
    "auto_" to "Android Auto",
    "hicar_" to "HiCar",
    "airplay_" to "AirPlay",
    "android_mirror_" to "Mirror",
    "dlna_" to "DLNA",
)

/** "<protocol> <link>", e.g. "CarPlay wireless"; a live call replaces the link word. */
internal fun carPlayChipText(state: CarPlayState): String {
    val mode = state.phoneMode.orEmpty()
    val protocol = PROJECTION_NAMES.entries.firstOrNull { mode.startsWith(it.key) }?.value
        ?: CARPLAY_NAME
    val detail = if (state.inCall) CALL_WORD else state.link?.name?.lowercase()

    return listOfNotNull(protocol, detail).joinToString(" ")
}

@Composable
private fun rememberCarPlayStatus(carEvents: CarEvents?): CarPlayState {
    val state by (carEvents?.carplayState?.collectAsStateSafe(initial = CarPlayState())
        ?: remember { mutableStateOf(CarPlayState()) })
    return state
}

// ---- Shared chip -----------------------------------------------------------

@Composable
private fun StatusChip(
    icon: ImageVector,
    description: String,
    tint: Color,
    text: String? = null,
    // Identity, not styling: the tag says *which* indicator this is, so a test can pin the
    // chip's presence while a redesign is free to change its icon, colour and wording.
    tag: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.testTag(tag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        if (text != null) {
            AutoSizeText(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = tint,
            )
        }
    }
}
