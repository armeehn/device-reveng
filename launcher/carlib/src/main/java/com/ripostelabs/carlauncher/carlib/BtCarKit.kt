package com.ripostelabs.carlauncher.carlib

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BtCarKit — the phone link on Riposte OS 0.2, through the stock stack's car-kit roles.
 *
 * On the vendor slot btsuite owns the BT module and the launcher reads its broadcasts
 * ([VendorBtDecode]). On 0.2 there is no btsuite: `os/overlay/props` turns on
 * `bluetooth.profile.{hfp.hf,a2dp.sink,avrcp.controller}.enabled`, and this class is the
 * reader and the call buttons. Started only when `McuOwner` runs (the slot is ours).
 *
 * ```
 *  phone (AG / A2DP source / AVRCP target)
 *    │ HFP · A2DP · AVRCP
 *    ▼
 *  com.android.bluetooth ── HeadsetClientService · A2dpSinkService · AvrcpControllerService
 *    │ getProfileProxy(16 / 11 / 12)            │ AudioTrack (btif_a2dp_sink.cc)
 *    │ + CONNECTION_STATE_CHANGED /             │ MediaSession (BluetoothMediaBrowserService)
 *    │   AG_CALL_CHANGED broadcasts             ▼
 *    ▼                                     audio HAL · NowPlayingRepository (unchanged)
 *  BtCarKit ─▶ BtCarKitSnapshot ─▶ BtCarKitMap.vendorView ─▶ CarEvents.feedVendorBt
 *           ◀─ answer / hangUp / dial  (BluetoothHeadsetClient, reflection)
 * ```
 *
 * The three proxies are `@SystemApi` (`BluetoothA2dpSink`, `BluetoothHeadsetClient`,
 * `BluetoothAvrcpController`), absent from the SDK jar: the ids are the AOSP ints
 * (`BluetoothProfile.java:154,162,177`, android-14.0.0_r1) and the per-profile calls go by
 * reflection, which the hidden-API rules allow for system APIs. Every method they need is
 * `BLUETOOTH_CONNECT` only (`BluetoothHeadsetClient.java:1182-1352`), already granted.
 *
 * Audio needs no bridge: `A2dpSinkStreamHandler` takes audio focus and the native sink feeds
 * an `AudioTrack`; `AvrcpControllerService` publishes a `MediaSessionCompat` the now-playing
 * card already picks up. Whether the vendor audio HAL routes that track to the amp is a car
 * test, as is every line below: the emulator farm has no radio.
 */
class BtCarKit(context: Context) {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val proxies = HashMap<Int, BluetoothProfile>()

    /** The HF client's call objects, kept for [hangUp]; parallel to the snapshot's calls. */
    private var callHandles: List<Any> = emptyList()

    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, TAG).apply { isDaemon = true }
    }

    private val _snapshot = MutableStateFlow(BtCarKitSnapshot())
    val snapshot: StateFlow<BtCarKitSnapshot> = _snapshot.asStateFlow()

    private val _vendorView = MutableStateFlow(VendorBtState())
    /** The snapshot as [VendorBtState], for [CarEvents.feedVendorBt]. */
    val vendorView: StateFlow<VendorBtState> = _vendorView.asStateFlow()

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            worker.execute {
                proxies[profile] = proxy
                refresh()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            worker.execute {
                proxies.remove(profile)
                refresh()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            worker.execute { refresh() }
        }
    }

    private var started = false

    /** Bind the three proxies and follow their broadcasts. No-op without an adapter. */
    fun start() {
        if (started) {
            return
        }
        val bt = adapter ?: run {
            Log.w(TAG, "no Bluetooth adapter; car-kit inert")
            return
        }
        started = true
        // The stack is another uid, so the receiver must be exported (minSdk 33).
        val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
        appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        for (id in PROFILES) {
            if (!bt.getProfileProxy(appContext, listener, id)) {
                Log.w(TAG, "getProfileProxy($id) refused")
            }
        }
        worker.schedule({ settle() }, BIND_SETTLE_MS, TimeUnit.MILLISECONDS)
        worker.execute { refresh() }
    }

    fun stop() {
        if (!started) {
            return
        }
        started = false
        runCatching { appContext.unregisterReceiver(receiver) }
        worker.execute {
            for ((id, proxy) in proxies) {
                adapter?.closeProfileProxy(id, proxy)
            }
            proxies.clear()
        }
        worker.shutdown()
    }

    /** Accept the ringing call (`acceptCall(device, CALL_ACCEPT_NONE)`). */
    fun answer() = hfCall { hf, device ->
        hf.method("acceptCall", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            .invoke(hf, device, CALL_ACCEPT_NONE)
    }

    /** Reject a ringing call, else terminate the lead call (all calls when none is known). */
    fun hangUp() = hfCall { hf, device ->
        val calls = _snapshot.value.calls
        val lead = BtCarKitMap.leadCall(calls)
        if (lead != null && (lead.state == HfCallState.INCOMING || lead.state == HfCallState.WAITING)) {
            hf.method("rejectCall", BluetoothDevice::class.java).invoke(hf, device)
            return@hfCall
        }
        val handle = lead?.let { callHandles.getOrNull(calls.indexOf(it)) }
        hf.method("terminateCall", BluetoothDevice::class.java, Class.forName(CALL_CLASS))
            .invoke(hf, device, handle)
    }

    fun dial(number: String) = hfCall { hf, device ->
        hf.method("dial", BluetoothDevice::class.java, String::class.java).invoke(hf, device, number)
    }

    /** Run [block] on the worker against the HF proxy and its connected phone, if any. */
    private fun hfCall(block: (BluetoothProfile, BluetoothDevice) -> Unit) {
        worker.execute {
            val hf = proxies[PROFILE_HEADSET_CLIENT] ?: return@execute
            val device = connectedDevice(hf) ?: return@execute
            runCatching { block(hf, device) }.onFailure { Log.w(TAG, "HF call control failed", it) }
        }
    }

    private fun settle() {
        _snapshot.value = _snapshot.value.copy(settled = true)
        refresh()
    }

    /** Re-read every proxy; each binder call is guarded so one dead proxy costs one field. */
    private fun refresh() {
        val now = System.currentTimeMillis()
        val hf = proxies[PROFILE_HEADSET_CLIENT]
        val sink = proxies[PROFILE_A2DP_SINK]
        val avrcp = proxies[PROFILE_AVRCP_CONTROLLER]
        val hfDevice = hf?.let(::connectedDevice)
        val sinkDevice = sink?.let(::connectedDevice)
        val handles = if (hf != null && hfDevice != null) currentCalls(hf, hfDevice) else emptyList()
        val calls = handles.map { HfCall(state = callState(it), number = callNumber(it)) }
        callHandles = handles

        val prev = _snapshot.value
        val next = prev.copy(
            adapterOn = adapter?.isEnabled == true,
            bound = proxies.keys.toSet(),
            phoneName = (hfDevice ?: sinkDevice)?.let(::deviceName),
            hfConnected = hfDevice != null,
            sinkConnected = sinkDevice != null,
            avrcpConnected = avrcp?.let(::connectedDevice) != null,
            calls = calls,
            audioPlaying = sink != null && sinkDevice != null && audioPlaying(sink, sinkDevice),
            activeSinceMs = BtCarKitMap.activeSince(prev.activeSinceMs, calls, now),
        )
        _snapshot.value = next
        _vendorView.value = BtCarKitMap.vendorView(next, now)
    }

    private fun connectedDevice(proxy: BluetoothProfile): BluetoothDevice? =
        runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()

    private fun deviceName(device: BluetoothDevice): String? =
        runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: device.address

    private fun currentCalls(hf: BluetoothProfile, device: BluetoothDevice): List<Any> = runCatching {
        (hf.method("getCurrentCalls", BluetoothDevice::class.java).invoke(hf, device) as? List<*>)
            ?.filterNotNull()
    }.getOrNull() ?: emptyList()

    private fun callState(call: Any): Int =
        runCatching { call.method("getState").invoke(call) as Int }.getOrDefault(HfCallState.TERMINATED)

    private fun callNumber(call: Any): String? =
        runCatching { call.method("getNumber").invoke(call) as? String }.getOrNull()

    private fun audioPlaying(sink: BluetoothProfile, device: BluetoothDevice): Boolean = runCatching {
        sink.method("isAudioPlaying", BluetoothDevice::class.java).invoke(sink, device) as Boolean
    }.getOrDefault(false)

    private fun Any.method(name: String, vararg types: Class<*>) = javaClass.getMethod(name, *types)

    companion object {
        private const val TAG = "BtCarKit"

        /** `BluetoothProfile.A2DP_SINK / AVRCP_CONTROLLER / HEADSET_CLIENT` (android-14.0.0_r1). */
        const val PROFILE_A2DP_SINK = 11
        const val PROFILE_AVRCP_CONTROLLER = 12
        const val PROFILE_HEADSET_CLIENT = 16
        val PROFILES = setOf(PROFILE_HEADSET_CLIENT, PROFILE_A2DP_SINK, PROFILE_AVRCP_CONTROLLER)

        /** `BluetoothHeadsetClient.CALL_ACCEPT_NONE` (`:719`). */
        private const val CALL_ACCEPT_NONE = 0
        private const val CALL_CLASS = "android.bluetooth.BluetoothHeadsetClientCall"

        /** A proxy that has not bound by then is a profile the stack does not run. */
        const val BIND_SETTLE_MS = 5_000L

        /** `BluetoothHeadsetClient.ACTION_*`, `BluetoothA2dpSink` / `BluetoothAvrcpController`. */
        private const val HF_PREFIX = "android.bluetooth.headsetclient.profile.action."
        val ACTIONS = listOf(
            HF_PREFIX + "CONNECTION_STATE_CHANGED",
            HF_PREFIX + "AG_CALL_CHANGED",
            HF_PREFIX + "AUDIO_STATE_CHANGED",
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED",
            BluetoothAdapter.ACTION_STATE_CHANGED,
        )
    }
}
