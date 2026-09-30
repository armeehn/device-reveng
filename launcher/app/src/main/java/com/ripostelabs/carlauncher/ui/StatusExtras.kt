package com.ripostelabs.carlauncher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import android.telephony.TelephonyManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * RAV4-198 — the stock status bar's SIM and USB icons (`LandStatusView.java:68-76`), as sources
 * for [StatusIndicators]. Same rule as the other chips: no source, no chip.
 *
 *   SIM   poll — TelephonyManager SIM state and signal level. Neither needs READ_PHONE_STATE,
 *                which the launcher does not hold; the network generation (4G/5G) does, so the
 *                chip shows bars only.
 *   USB   push — MEDIA_MOUNTED / UNMOUNTED / EJECT broadcasts, re-reading the removable
 *                volumes. Stock checks the same thing (`StatusBarViewProxy.java:128-141`): a
 *                mounted stick or card, not a bare USB device.
 */

/** One storage volume, reduced to what the USB chip needs. */
internal data class StorageVolumeInfo(val removable: Boolean, val state: String)

/** Signal bars (0..4) for a usable SIM, or null when there is none to show. */
internal fun simBars(simState: Int, level: Int?): Int? {
    if (simState != TelephonyManager.SIM_STATE_READY) {
        return null
    }
    return (level ?: 0).coerceIn(0, SIM_MAX_BARS)
}

/** True when a removable volume (USB stick, SD card) is mounted. */
internal fun usbMounted(volumes: List<StorageVolumeInfo>): Boolean =
    volumes.any { it.removable && it.state == Environment.MEDIA_MOUNTED }

/** The SIM's bars, re-read on a slow poll. Null on a unit with no modem or no SIM. */
@Composable
internal fun rememberSimBars(context: Context): State<Int?> = produceState<Int?>(initialValue = null) {
    val tm = context.getSystemService(TelephonyManager::class.java) ?: return@produceState
    while (true) {
        value = withContext(Dispatchers.IO) {
            runCatching { simBars(tm.simState, tm.signalStrength?.level) }.getOrNull()
        }
        delay(SIM_POLL_MS)
    }
}

/** True while a USB stick or card is mounted, pushed by the media broadcasts. */
@Composable
internal fun rememberUsbMounted(context: Context): State<Boolean> = produceState(initialValue = false) {
    val sm = context.getSystemService(StorageManager::class.java) ?: return@produceState
    val read = {
        usbMounted(
            runCatching {
                sm.storageVolumes.map { StorageVolumeInfo(it.isRemovable, it.state) }
            }.getOrDefault(emptyList()),
        )
    }
    value = read()

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            value = read()
        }
    }
    val filter = IntentFilter().apply {
        USB_MEDIA_ACTIONS.forEach(::addAction)
        // Media broadcasts carry a file:// data URI; without the scheme none of them match.
        addDataScheme("file")
    }
    runCatching { context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) }
    awaitDispose { runCatching { context.unregisterReceiver(receiver) } }
}

internal const val SIM_MAX_BARS = 4
private const val SIM_POLL_MS = 10_000L

private val USB_MEDIA_ACTIONS = listOf(
    Intent.ACTION_MEDIA_MOUNTED,
    Intent.ACTION_MEDIA_UNMOUNTED,
    Intent.ACTION_MEDIA_EJECT,
    Intent.ACTION_MEDIA_REMOVED,
    Intent.ACTION_MEDIA_BAD_REMOVAL,
)
