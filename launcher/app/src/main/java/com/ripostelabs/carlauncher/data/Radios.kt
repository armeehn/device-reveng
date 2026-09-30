package com.ripostelabs.carlauncher.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.provider.Settings
import com.ripostelabs.carlauncher.carlib.RootShell
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * RAV4-174: Wi-Fi and Bluetooth on and off from the shade, as stock's pull-down has them
 * (DropDownItemOval.java:188-256), and the Android pages behind them.
 *
 *     tile tap ──▶ switch() ──▶ root `svc wifi|bluetooth enable|disable`
 *     tile     ◀── state()  ◀── WIFI_STATE_CHANGED / BluetoothAdapter.ACTION_STATE_CHANGED
 *
 * The tile shows the live broadcast, never a stored wish, so AccStandby's ACC off and its
 * restore on wake (BT on, Wi-Fi only if it was on) show up as they happen and nothing here
 * fights them. The verbs are the ones AccStandby runs. Without root, [switch] fails and the
 * tile opens Android's page instead: WifiManager.setWifiEnabled is refused to apps since API 29.
 */
object Radios {

    enum class Radio { WIFI, BLUETOOTH }

    enum class Power { ON, OFF }

    /** Android's pages for the shade's long press and the Settings rows. */
    enum class Page { WIFI, BLUETOOTH, HOTSPOT }

    private const val NO_STATE = -1

    /** Settings' tether page has no public action; its activity is stable since Android 4. */
    private const val SETTINGS_PKG = "com.android.settings"
    private const val TETHER_ACTIVITY = "com.android.settings.TetherSettings"

    fun command(radio: Radio, power: Power): String {
        val service = when (radio) {
            Radio.WIFI -> "wifi"
            Radio.BLUETOOTH -> "bluetooth"
        }
        val verb = when (power) {
            Power.ON -> "enable"
            Power.OFF -> "disable"
        }
        return "svc $service $verb"
    }

    fun switch(radio: Radio, power: Power, shell: (String) -> RootShell.Result = RootShell::exec): Boolean =
        shell(command(radio, power)).ok

    fun wifiOn(state: Int): Boolean? = when (state) {
        WifiManager.WIFI_STATE_ENABLED, WifiManager.WIFI_STATE_ENABLING -> true
        WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING -> false
        else -> null
    }

    fun bluetoothOn(state: Int): Boolean? = when (state) {
        BluetoothAdapter.STATE_ON, BluetoothAdapter.STATE_TURNING_ON -> true
        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> false
        else -> null
    }

    fun page(radio: Radio): Page = when (radio) {
        Radio.WIFI -> Page.WIFI
        Radio.BLUETOOTH -> Page.BLUETOOTH
    }

    fun intent(page: Page): Intent {
        val intent = when (page) {
            Page.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            Page.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            Page.HOTSPOT -> Intent().setClassName(SETTINGS_PKG, TETHER_ACTIVITY)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens [page]; the hotspot page falls back to the wireless page on a build without it. */
    fun open(context: Context, page: Page) {
        val opened = runCatching { context.startActivity(intent(page)) }.isSuccess
        if (opened || page != Page.HOTSPOT) {
            return
        }

        runCatching {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** On, off, or null while unknown (no adapter, or a state in between). */
    fun state(context: Context, radio: Radio): Flow<Boolean?> = callbackFlow {
        val (action, extra) = when (radio) {
            Radio.WIFI -> WifiManager.WIFI_STATE_CHANGED_ACTION to WifiManager.EXTRA_WIFI_STATE
            Radio.BLUETOOTH -> BluetoothAdapter.ACTION_STATE_CHANGED to BluetoothAdapter.EXTRA_STATE
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(read(radio, intent.getIntExtra(extra, NO_STATE)))
            }
        }

        trySend(read(radio, current(context, radio)))
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    private fun read(radio: Radio, state: Int): Boolean? = when (radio) {
        Radio.WIFI -> wifiOn(state)
        Radio.BLUETOOTH -> bluetoothOn(state)
    }

    private fun current(context: Context, radio: Radio): Int = runCatching {
        when (radio) {
            Radio.WIFI -> context.getSystemService(WifiManager::class.java)?.wifiState
            Radio.BLUETOOTH -> context.getSystemService(BluetoothManager::class.java)?.adapter?.state
        }
    }.getOrNull() ?: NO_STATE
}
