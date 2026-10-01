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
 *     recover() at launcher start: the radios a standby turned off and no leave turned back on
 *
 * The radio states persist. A wake that fails (the owner presses RST) or a B+ cut skips leave,
 * and every later boot had Wi-Fi and BT off, so wireless CarPlay never came up (car,
 * 2026-10-01). So enter saves what it found in [MARKER_PROP] before switching anything off:
 *
 *     enter    read wifi, bt, location, airplane ─▶ marker "1,1,3,0" ─▶ RADIOS_OFF
 *     leave    restore from the states in memory ─▶ clear the marker
 *     recover  marker set? restore from it        ─▶ clear the marker
 *
 * Restore turns back on only what was on. A radio the owner had off stays off.
 *     darken()  a sleep key: PowerManager.goToSleep, a no-op on a dark panel
 *
 * [decoder] re-arms the PR2000: a kernel suspend holds it in reset and its resume drops the
 * channel (zxw_pr2000_suspend/_resume, share carlauncher/pr2000-driver.md).
 */
class AccStandby(
    private val shell: (String) -> RootShell.Result = { RootShell.exec(it) },
    private val decoder: () -> Unit = {},
) : McuSleepWake.Standby {

    /** The radios as enter found them; null outside standby. */
    @Volatile
    private var saved: Radios? = null

    /** The port's role before [enter]; [leave] puts PERIPHERAL back once. */
    @Volatile
    private var usbWas = UsbRole.UNKNOWN

    override fun enter() {
        // A marker already set means the radios are off from a standby never left: keep the
        // owner's states it holds, not the off states read now.
        val held = Radios.decode(read(MARKER_GET))
        val radios = held ?: readRadios()
        saved = radios
        if (held == null) {
            run(MARKER_SET + RootShell.quote(radios.encode()))
        }

        run(RADIOS_OFF)
        run(CAMERA_OFF)
        usbSleep()
    }

    override fun leave() {
        usbWake()
        run(CAMERA_ON)
        decoder()

        val radios = saved
        if (radios == null) {
            recover()
            return
        }

        saved = null
        restore(radios)
    }

    /** At launcher start: put back the radios of a standby that was never left, then forget it. */
    fun recover() {
        val text = read(MARKER_GET)
        if (text.isEmpty()) {
            return
        }

        // Junk in the marker restores nothing; clearing it stops a retry on every boot.
        val radios = Radios.decode(text)
        if (radios == null) {
            Log.w(LOG_TAG, "unreadable marker '$text'")
            run(MARKER_CLEAR)
            return
        }

        Log.i(LOG_TAG, "standby was never left; restoring $radios")
        restore(radios)
    }

    override fun darken() = run(DARKEN)

    /** Each radio back as it was before standby, airplane mode first so the others can start. */
    private fun restore(radios: Radios) {
        if (!radios.airplane) {
            run(AIRPLANE_OFF)
        }

        if (radios.location != LOCATION_OFF) {
            run("$LOCATION_PUT ${radios.location}")
        }

        if (radios.bluetooth) {
            run(BT_ON)
        }

        if (radios.wifi) {
            run(WIFI_ON)
        }

        run(MARKER_CLEAR)
    }

    private fun readRadios() = Radios(
        wifi = read(WIFI_STATE) != SETTING_OFF,
        bluetooth = read(BT_STATE) != SETTING_OFF,
        location = read(LOCATION_STATE).toIntOrNull() ?: LOCATION_ON,
        airplane = read(AIRPLANE_STATE) == SETTING_ON,
    )

    private fun read(command: String): String = shell(command).out.firstOrNull()?.trim().orEmpty()

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

    /** The radios before standby. Wire form "wifi,bt,location,airplane", e.g. "1,1,3,0". */
    private data class Radios(val wifi: Boolean, val bluetooth: Boolean, val location: Int, val airplane: Boolean) {
        fun encode(): String = listOf(wifi.bit(), bluetooth.bit(), location, airplane.bit()).joinToString(SEP)

        companion object {
            private const val SEP = ","
            private const val FIELDS = 4

            fun decode(text: String): Radios? {
                val f = text.split(SEP)
                if (f.size != FIELDS) {
                    return null
                }

                val location = f[2].toIntOrNull() ?: return null
                return Radios(f[0] == SETTING_ON, f[1] == SETTING_ON, location, f[3] == SETTING_ON)
            }

            private fun Boolean.bit() = if (this) SETTING_ON else SETTING_OFF
        }
    }

    companion object {
        private const val LOG_TAG = "AccStandby"

        /** Stock's SYS_WIFI_STATE memory (:3527-3532); "0" is off. */
        const val WIFI_STATE = "settings get global wifi_on"
        const val BT_STATE = "settings get global bluetooth_on"
        const val LOCATION_STATE = "settings get secure location_mode"
        const val AIRPLANE_STATE = "settings get global airplane_mode_on"
        private const val SETTING_OFF = "0"
        private const val SETTING_ON = "1"

        /** Settings.Secure.LOCATION_MODE_OFF and _HIGH_ACCURACY (stock's accOn value). */
        private const val LOCATION_OFF = 0
        private const val LOCATION_ON = 3

        /** Persist, so it outlives the RST or B+ cut that skipped leave. Empty is no marker. */
        const val MARKER_PROP = "persist.riposte.standby.radios"
        const val MARKER_GET = "getprop $MARKER_PROP"
        const val MARKER_SET = "setprop $MARKER_PROP "
        const val MARKER_CLEAR = "setprop $MARKER_PROP ''"

        const val RADIOS_OFF = "svc wifi disable; svc bluetooth disable; " +
            "settings put secure location_mode 0; cmd connectivity airplane-mode enable"
        const val CAMERA_OFF = "setprop sys.acc.state 0; " +
            "echo PowerManagerService.Display > /sys/power/wake_unlock"
        const val CAMERA_ON = "echo PowerManagerService.Display > /sys/power/wake_lock; " +
            "setprop sys.acc.state 1"
        const val AIRPLANE_OFF = "cmd connectivity airplane-mode disable"
        private const val LOCATION_PUT = "settings put secure location_mode"
        const val BT_ON = "svc bluetooth enable"
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
