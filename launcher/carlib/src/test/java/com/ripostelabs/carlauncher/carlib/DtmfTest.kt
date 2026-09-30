package com.ripostelabs.carlauncher.carlib

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtmfTest {

    /** The shape of `BluetoothHeadsetClient.sendDTMF(BluetoothDevice, byte)`. */
    class FakeHeadsetClient {
        val sent = mutableListOf<Byte>()

        @Suppress("unused", "FunctionName")
        fun sendDTMF(device: BluetoothDevice?, code: Byte): Boolean {
            sent += code
            return true
        }
    }

    @Test
    fun `pad keys map to their ASCII code`() {
        assertEquals('5'.code.toByte(), Dtmf.code('5'))
        assertEquals('*'.code.toByte(), Dtmf.code('*'))
        assertEquals('#'.code.toByte(), Dtmf.code('#'))
        assertEquals('A'.code.toByte(), Dtmf.code('A'))
    }

    @Test
    fun `anything outside the HFP tone set sends nothing`() {
        assertNull(Dtmf.code('+'))
        assertNull(Dtmf.code('x'))
        assertNull(Dtmf.code('E'))
    }

    @Test
    fun `send calls sendDTMF on the HF client with the tone byte`() {
        val hf = FakeHeadsetClient()
        assertTrue(Dtmf.send(hf, null, '7'))
        assertEquals(listOf('7'.code.toByte()), hf.sent)
    }

    @Test
    fun `send skips a key with no tone`() {
        val hf = FakeHeadsetClient()
        assertFalse(Dtmf.send(hf, null, '+'))
        assertTrue(hf.sent.isEmpty())
    }
}
