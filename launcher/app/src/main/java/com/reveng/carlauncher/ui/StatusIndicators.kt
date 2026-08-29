package com.reveng.carlauncher.ui

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
import androidx.compose.material.icons.filled.NetworkWifi1Bar
import androidx.compose.material.icons.filled.NetworkWifi2Bar
import androidx.compose.material.icons.filled.NetworkWifi3Bar
import androidx.compose.material.icons.filled.SignalWifi0Bar
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.data.BrightnessController
import com.reveng.carlauncher.ui.theme.carShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * v3.1 — always-visible status chips for the top bar: Wi-Fi, Bluetooth, volume, brightness
 * (ROADMAP "Status you can see"). Display-only per LAUNCHER_DESIGN §2.1 — the strip is glance
 * data and the SWC focus ring does not visit it; tapping the group opens the existing Quick
 * Controls panel, so status lives in the bar and control sits one tap below it.
 *
 * Every chip degrades by disappearing (or greying) rather than freezing: no Bluetooth adapter
 * -> no chip, EventService unbound -> no volume chip. A chip that lies is worse than no chip.
 *
 * Update paths, cheapest first:
 *   Wi-Fi       push  — ConnectivityManager.NetworkCallback (signal via onCapabilitiesChanged)
 *   Bluetooth   push  — adapter state + connection-state broadcasts
 *   brightness  push  — ContentObserver on Settings.System.SCREEN_BRIGHTNESS
 *   volume      poll  — the vendor AIDL exposes no volume event on main yet, so a slow
 *                       [VOLUME_POLL_MS] read + an immediate re-read when Quick Controls
 *                       closes ([refreshKey]). Swap to the event flow when one lands.
 */

private const val VOLUME_POLL_MS = 5_000L
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
}

@Composable
fun StatusIndicators(
    carService: CarService?,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
    onOpen: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext

    StatusIndicatorsRow(
        wifi = rememberWifiStatus(context),
        bt = rememberBluetoothStatus(context),
        volume = rememberVolumeStatus(carService, refreshKey),
        brightnessPercent = rememberBrightnessPercent(context, refreshKey),
        modifier = modifier,
        onOpen = onOpen,
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
        !wifi.enabled || !wifi.connected -> Color.Gray
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
                Context.RECEIVER_EXPORTED,
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
        !bt.on -> Triple(Icons.Filled.BluetoothDisabled, "Bluetooth off", Color.Gray)
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

@Composable
private fun rememberBluetoothStatus(context: Context): BtStatus {
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }

    // getState/isEnabled need no permission on API 33; the *count* paths (profile probe,
    // connection-state broadcasts) want BLUETOOTH_CONNECT, a runtime permission on 31+.
    // Everything is runCatching-guarded: ungranted -> the chip degrades to on/off, no count.
    // On the rooted unit: pm grant com.reveng.carlauncher android.permission.BLUETOOTH_CONNECT
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
            runCatching { context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) }

            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }
    return BtStatus(present = adapter != null, on = on, connectedCount = connected.size)
}

// ---- Volume ----------------------------------------------------------------

internal data class VolumeStatus(
    val available: Boolean,
    val level: Int,
    val muted: Boolean,
)

@Composable
private fun VolumeChip(volume: VolumeStatus) {
    if (volume.muted) {
        StatusChip(
            icon = Icons.AutoMirrored.Filled.VolumeOff,
            description = "Muted",
            tint = Color.Gray,
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

@Composable
private fun rememberVolumeStatus(carService: CarService?, refreshKey: Int): VolumeStatus {
    var status by remember { mutableStateOf(VolumeStatus(available = false, level = 0, muted = false)) }

    LaunchedEffect(carService, refreshKey) {
        if (carService == null) {
            status = VolumeStatus(available = false, level = 0, muted = false)
            return@LaunchedEffect
        }
        while (true) {
            val level = withContext(Dispatchers.IO) {
                runCatching { carService.getMainVolume() }.getOrNull()
            }
            val muted = withContext(Dispatchers.IO) {
                runCatching { carService.isMuteOn() }.getOrDefault(false)
            }
            // null = EventService unbound: the chip disappears rather than showing stale.
            status = if (level == null) {
                VolumeStatus(available = false, level = 0, muted = false)
            } else {
                VolumeStatus(available = true, level = level, muted = muted)
            }
            delay(VOLUME_POLL_MS)
        }
    }
    return status
}

// ---- Brightness ------------------------------------------------------------

@Composable
private fun rememberBrightnessPercent(context: Context, refreshKey: Int): Int {
    var percent by remember { mutableIntStateOf(BrightnessController.currentPercent(context)) }

    LaunchedEffect(refreshKey) {
        percent = BrightnessController.currentPercent(context)
    }

    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                percent = BrightnessController.currentPercent(context)
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
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = tint,
            )
        }
    }
}
