package com.ripostelabs.carlauncher.carlib

/**
 * RAV4-178 — one bonded phone, one verb, as the suite Bluetooth app (`com.ripostelabs.bluetooth`)
 * asks for it. Connect, disconnect and forget need BLUETOOTH_PRIVILEGED (the HF client and A2DP
 * sink `connect` / `disconnect`, `BluetoothDevice.removeBond`); the launcher is the priv-app that
 * holds it, so the suite app sends this broadcast instead of signing itself onto the image.
 *
 * ```
 *  suite BT app ──BT_DEVICE {op, address}──▶ launcher receiver ──▶ BtCarKit.device(command)
 *               (sender holds BLUETOOTH_CONNECT)
 * ```
 */
data class BtDeviceCommand(val op: Op, val address: String) {

    enum class Op { CONNECT, DISCONNECT, FORGET }

    companion object {
        const val ACTION = "com.ripostelabs.carlauncher.action.BT_DEVICE"
        const val EXTRA_OP = "op"
        const val EXTRA_ADDRESS = "address"

        /** `AA:BB:CC:DD:EE:FF`, the only shape `BluetoothAdapter.checkBluetoothAddress` accepts. */
        private val ADDRESS = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

        /** The broadcast's two extras; null for an unknown verb or a malformed address. */
        fun parse(op: String?, address: String?): BtDeviceCommand? {
            val verb = Op.entries.firstOrNull { it.name == op } ?: return null
            val mac = address?.uppercase()?.takeIf(ADDRESS::matches) ?: return null
            return BtDeviceCommand(verb, mac)
        }
    }
}
