package com.ripostelabs.carlauncher.data

import android.bluetooth.BluetoothAdapter
import android.net.wifi.WifiManager
import com.ripostelabs.carlauncher.carlib.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadiosTest {

    // RAV4-174: the same `svc` verbs AccStandby uses, so a shade toggle and the ACC restore
    // drive the radio one way.
    @Test
    fun commandsAreSvcVerbs() {
        assertEquals("svc wifi enable", Radios.command(Radios.Radio.WIFI, Radios.Power.ON))
        assertEquals("svc wifi disable", Radios.command(Radios.Radio.WIFI, Radios.Power.OFF))
        assertEquals("svc bluetooth enable", Radios.command(Radios.Radio.BLUETOOTH, Radios.Power.ON))
        assertEquals("svc bluetooth disable", Radios.command(Radios.Radio.BLUETOOTH, Radios.Power.OFF))
    }

    // A radio turning on already reads as on, so the tile does not flicker back mid-switch.
    @Test
    fun wifiStates() {
        assertEquals(true, Radios.wifiOn(WifiManager.WIFI_STATE_ENABLED))
        assertEquals(true, Radios.wifiOn(WifiManager.WIFI_STATE_ENABLING))
        assertEquals(false, Radios.wifiOn(WifiManager.WIFI_STATE_DISABLED))
        assertEquals(false, Radios.wifiOn(WifiManager.WIFI_STATE_DISABLING))
        assertNull(Radios.wifiOn(WifiManager.WIFI_STATE_UNKNOWN))
    }

    @Test
    fun bluetoothStates() {
        assertEquals(true, Radios.bluetoothOn(BluetoothAdapter.STATE_ON))
        assertEquals(true, Radios.bluetoothOn(BluetoothAdapter.STATE_TURNING_ON))
        assertEquals(false, Radios.bluetoothOn(BluetoothAdapter.STATE_OFF))
        assertEquals(false, Radios.bluetoothOn(BluetoothAdapter.STATE_TURNING_OFF))
        assertNull(Radios.bluetoothOn(BluetoothAdapter.ERROR))
    }

    // No root: the switch reports failure, so the tile opens Android's page instead.
    @Test
    fun switchReportsTheShellResult() {
        val sent = mutableListOf<String>()
        val ok = Radios.switch(Radios.Radio.WIFI, Radios.Power.OFF) { sent += it; RootShell.Result(0, emptyList(), emptyList()) }
        val refused = Radios.switch(Radios.Radio.BLUETOOTH, Radios.Power.ON) { RootShell.Result(1, emptyList(), listOf("no su")) }

        assertTrue(ok)
        assertFalse(refused)
        assertEquals(listOf("svc wifi disable"), sent)
    }
}
