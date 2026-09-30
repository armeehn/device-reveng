package com.ripostelabs.carlauncher.carlib

import android.util.Log

/**
 * AccStandby — the Android half of stock's ACC sleep and wake, through the root shell.
 *
 *     enter()   Utils.accOff (Utils.java:178-190): wifi, BT, location off, airplane mode on
 *               sys.acc.state 0 (EventService.java:437-443): ais_server's sleep gate closes
 *               wake_unlock PowerManagerService.Display: riposte-ais.sh took it for ais_server,
 *               and while it is held the kernel never suspends (car, 2026-09-26 05:19:10)
 *               USB: a peripheral port turns host (stock's port is always host), then
 *               sys.usb_power 1 (EventService.java:3556, vendor init: /sys/touch_type/usb_power)
 *               A peripheral dwc3 facing zero's adb holds the `4e00000.ssusb` wakeup source,
 *               so the kernel never suspended and the MCU cold-booted the unit (car,
 *               2026-09-30 10:46:12). The stored role (persist.riposte.usb.role) is untouched.
 *     leave()   sys.usb_power 0 (:3470) and the peripheral role back, then the camera gates, then [decoder], then Utils.accOn (:165-175): airplane
 *               off, location on, BT on; wifi only if it was on (setAccWakeUp, :3456-3460)
 *     darken()  a sleep key: PowerManager.goToSleep, a no-op on a dark panel
 *
 * [decoder] re-arms the PR2000: a kernel suspend holds it in reset and its resume drops the
 * channel (zxw_pr2000_suspend/_resume, share carlauncher/pr2000-driver.md).
 */
class AccStandby(
    private val shell: (String) -> RootShell.Result = { RootShell.exec(it) },
    private val decoder: () -> Unit = {},
) : McuSleepWake.Standby {

    @Volatile
    private var wifiWasOn = true

    /** The port's role before [enter]; [leave] puts PERIPHERAL back once. */
    @Volatile
    private var usbWas = UsbRole.UNKNOWN

    override fun enter() {
        wifiWasOn = shell(WIFI_STATE).out.firstOrNull()?.trim() != WIFI_OFF
        run(RADIOS_OFF)
        run(CAMERA_OFF)
        usbSleep()
    }

    override fun leave() {
        usbWake()
        run(CAMERA_ON)
        decoder()
        run(RADIOS_ON)
        if (wifiWasOn) {
            run(WIFI_ON)
        }
    }

    override fun darken() = run(DARKEN)

    /** The port faces host with its power cut, so its wakeup source lets go. */
    private fun usbSleep() {
        usbWas = UsbRole.read(shell)
        if (usbWas == UsbRole.PERIPHERAL) {
            run(USB_HOST)
        }

        run(USB_POWER_OFF)
    }

    /** Power first, then the role the port had: zero's adb on a bench image. */
    private fun usbWake() {
        run(USB_POWER_ON)
        if (usbWas != UsbRole.PERIPHERAL) {
            return
        }

        usbWas = UsbRole.UNKNOWN
        run(USB_PERIPHERAL)
    }

    private fun run(command: String) {
        val result = shell(command)
        Log.i(LOG_TAG, "$command -> ${result.code}")
    }

    companion object {
        private const val LOG_TAG = "AccStandby"

        /** Stock's SYS_WIFI_STATE memory (:3527-3532); "0" is off. */
        const val WIFI_STATE = "settings get global wifi_on"
        private const val WIFI_OFF = "0"

        const val RADIOS_OFF = "svc wifi disable; svc bluetooth disable; " +
            "settings put secure location_mode 0; cmd connectivity airplane-mode enable"
        const val CAMERA_OFF = "setprop sys.acc.state 0; " +
            "echo PowerManagerService.Display > /sys/power/wake_unlock"
        const val CAMERA_ON = "echo PowerManagerService.Display > /sys/power/wake_lock; " +
            "setprop sys.acc.state 1"
        const val RADIOS_ON = "cmd connectivity airplane-mode disable; " +
            "settings put secure location_mode 3; svc bluetooth enable"
        const val WIFI_ON = "svc wifi enable"
        const val DARKEN = "input keyevent KEYCODE_SLEEP"

        /** Stock's sleep and wake values (EventService.java:3556, :3470). */
        const val USB_POWER_OFF = "setprop sys.usb_power 1"
        const val USB_POWER_ON = "setprop sys.usb_power 0"

        /** The node only, never [UsbRole.ROLE_PROP]: init would replay a standby role at boot. */
        const val USB_HOST = "printf host > ${UsbRole.MODE_NODE}"
        const val USB_PERIPHERAL = "setprop sys.usb.config adb; printf peripheral > ${UsbRole.MODE_NODE}"
    }
}
