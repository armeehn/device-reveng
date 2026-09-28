package com.ripostelabs.carlauncher.carlib

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

/**
 * [PanLink] over `BluetoothPan`, a `@SystemApi` class absent from the SDK jar: the profile id
 * is the AOSP int and the calls go by reflection, as in [BtCarKit].
 *
 * `setConnectionPolicy(ALLOWED)` is the "Internet access" switch in Settings; PanService
 * dials the phone from it. The hidden `connect` is asked too, for a policy that was already
 * allowed. Both need `BLUETOOTH_PRIVILEGED`, granted through the image's privapp allowlist.
 * Until the proxy binds, [connect] reports a refusal and [PhoneInternet] retries.
 */
class AndroidPanLink(context: Context) : PanLink {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile
    private var pan: BluetoothProfile? = null

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            pan = proxy
        }

        override fun onServiceDisconnected(profile: Int) {
            pan = null
        }
    }

    /** Bind the PAN proxy. No-op without an adapter. */
    fun start() {
        val bt = adapter ?: run {
            Log.w(TAG, "no Bluetooth adapter; phone internet inert")
            return
        }
        if (!bt.getProfileProxy(appContext, listener, PROFILE_PAN)) {
            Log.w(TAG, "getProfileProxy(PAN) refused")
        }
    }

    fun stop() {
        pan?.let { adapter?.closeProfileProxy(PROFILE_PAN, it) }
        pan = null
    }

    override fun bonded(): List<BtPeer> =
        runCatching { adapter?.bondedDevices?.map(::peer) }.getOrNull().orEmpty()

    override fun state(address: String): PanState {
        val proxy = pan ?: return PanState.DISCONNECTED
        val device = device(address) ?: return PanState.DISCONNECTED
        return runCatching { PanState.of(proxy.getConnectionState(device)) }.getOrDefault(PanState.DISCONNECTED)
    }

    override fun connect(address: String): Boolean {
        val proxy = pan ?: return false
        val device = device(address) ?: return false

        // The policy call dials by itself (PanService.setConnectionPolicy); its result decides.
        val allowed = runCatching {
            proxy.method("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
                .invoke(proxy, device, CONNECTION_POLICY_ALLOWED) as Boolean
        }.onFailure { Log.w(TAG, "PAN setConnectionPolicy $address failed", it) }.getOrDefault(false)

        runCatching { proxy.method("connect", BluetoothDevice::class.java).invoke(proxy, device) }
            .onFailure { Log.d(TAG, "PAN connect $address not callable: ${it.javaClass.simpleName}") }
        return allowed
    }

    private fun device(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    private fun peer(device: BluetoothDevice): BtPeer = BtPeer(
        address = device.address,
        name = runCatching { device.name }.getOrNull(),
        // `BluetoothDevice.isConnected()` is @SystemApi: the ACL link, not a profile.
        aclConnected = runCatching { device.method("isConnected").invoke(device) as Boolean }.getOrDefault(false),
    )

    private fun Any.method(name: String, vararg types: Class<*>) = javaClass.getMethod(name, *types)

    private companion object {
        const val TAG = "PhoneInternet"

        /** `BluetoothProfile.PAN` (android-14.0.0_r1). */
        const val PROFILE_PAN = 5

        /** `BluetoothProfile.CONNECTION_POLICY_ALLOWED`. */
        const val CONNECTION_POLICY_ALLOWED = 100
    }
}
