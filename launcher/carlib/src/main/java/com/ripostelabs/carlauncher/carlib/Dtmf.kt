package com.ripostelabs.carlauncher.carlib

import android.bluetooth.BluetoothDevice

/**
 * RAV4-159 — touch tones during a call, on Riposte OS 0.2.
 *
 * Stock btsuite sends each pad key of an active call to its module (`BTFloatWndLandscape
 * .java:427`, event 7, `CMD_HSHF_SEND_DTMF`). On 0.2 the stack's HF client does it:
 * `BluetoothHeadsetClient.sendDTMF(device, code)` (@SystemApi, android-14.0.0_r1), which the
 * stack turns into `AT+VTS=<code>` to the phone. The code is the key's ASCII byte.
 */
object Dtmf {

    /** HFP 1.8 §4.28: the tones AT+VTS carries. */
    private val TONES = ('0'..'9').toSet() + setOf('*', '#') + ('A'..'D').toSet()

    private const val METHOD = "sendDTMF"

    fun code(key: Char): Byte? = if (key in TONES) key.code.toByte() else null

    /** Send [key] through [hf], a `BluetoothHeadsetClient` proxy; false when [key] has no tone. */
    fun send(hf: Any, device: BluetoothDevice?, key: Char): Boolean {
        val code = code(key) ?: return false

        hf.javaClass.getMethod(METHOD, BluetoothDevice::class.java, Byte::class.javaPrimitiveType!!)
            .invoke(hf, device, code)
        return true
    }
}
