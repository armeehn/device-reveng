package com.ripostelabs.carlauncher.input

import com.ripostelabs.carlauncher.carlib.McuOwner
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import com.ripostelabs.carlauncher.carlib.PowerKeyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a short press of the POWER key does (RAV4-156). Stock reads `Sys_Power_key_set`
 * (onPowerClicked, EventService.java:13824-13880): 0 blacks the screen, 1 enters standby. Our
 * default is standby: stock's hard key always powers off (EventService.java:2695-2698), and
 * the SysVar only steers its soft Power button. A dark panel lights on any key, and that press
 * does nothing else.
 */
class PowerKeyRouterTest {

    private class FakePanel : PowerKeyRouter.Panel {
        override var dark = false
        var darkened = 0

        override fun darken() {
            dark = true
            darkened++
        }

        override fun light() {
            dark = false
        }
    }

    private val standbyKeys = mutableListOf<Int>()
    private val standby = object : McuOwner.Listener {
        override fun onPanelKey(key: McuOwnerProtocol.PanelKey) {
            standbyKeys += key.code
        }
    }
    private val panel = FakePanel()

    private fun press(code: Int) = McuOwnerProtocol.PanelKey(code, status = 0)

    @Test
    fun blankKeepsTheHardKeyStandby() {
        assertEquals(PowerKeyMode.STANDBY, PowerKeyMode.of(raw = null))
        assertEquals(PowerKeyMode.STANDBY, PowerKeyMode.of(raw = ""))
        assertEquals(PowerKeyMode.SCREEN_OFF, PowerKeyMode.of(raw = "0"))
        assertEquals(PowerKeyMode.STANDBY, PowerKeyMode.of(raw = "1"))
    }

    @Test
    fun screenOffChoiceDarkensAndNeverArmsStandby() {
        val router = PowerKeyRouter({ PowerKeyMode.SCREEN_OFF }, standby, panel)

        router.onPanelKey(press(McuOwnerProtocol.Key.POWER))

        assertTrue(panel.dark)
        assertTrue("the ACC standby timer is not armed", standbyKeys.isEmpty())
    }

    @Test
    fun standbyChoiceKeepsTheOldPath() {
        val router = PowerKeyRouter({ PowerKeyMode.STANDBY }, standby, panel)

        router.onPanelKey(press(McuOwnerProtocol.Key.POWER))

        assertEquals(listOf(McuOwnerProtocol.Key.POWER), standbyKeys)
        assertFalse(panel.dark)
    }

    @Test
    fun anyKeyLightsADarkPanelAndStopsThere() {
        val router = PowerKeyRouter({ PowerKeyMode.STANDBY }, standby, panel)
        panel.darken()

        router.onPanelKey(press(McuOwnerProtocol.Key.POWER))

        assertFalse(panel.dark)
        assertTrue("the waking press does not also sleep the unit", standbyKeys.isEmpty())
    }

    @Test
    fun secondPowerPressLightsTheScreen() {
        val router = PowerKeyRouter({ PowerKeyMode.SCREEN_OFF }, standby, panel)

        router.onPanelKey(press(McuOwnerProtocol.Key.POWER))
        router.onPanelKey(press(McuOwnerProtocol.Key.POWER))

        assertFalse(panel.dark)
        assertEquals(1, panel.darkened)
    }

    @Test
    fun otherKeysPassWhileLit() {
        val router = PowerKeyRouter({ PowerKeyMode.SCREEN_OFF }, standby, panel)

        router.onPanelKey(press(McuOwnerProtocol.Key.NEXT))

        assertFalse(panel.dark)
        assertTrue(standbyKeys.isEmpty())
    }
}
