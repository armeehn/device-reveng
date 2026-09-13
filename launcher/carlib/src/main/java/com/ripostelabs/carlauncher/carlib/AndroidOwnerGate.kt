package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.content.pm.PackageManager

/**
 * AndroidOwnerGate — [McuOwner.Gate] answered from the running system.
 *
 * eventcenter present = the vendor owns the port, full stop. The OS flag is
 * `ro.riposte.os.car_owner`, written by `os/build.sh --car-owner` (and by profile gsi) into
 * build.prop; read with `getprop` because `SystemProperties` is a hidden API.
 *
 * The same reader answers which carrier the links ride on ([McuLinkSpec]): `riposte.mcu.link`
 * and `riposte.canbus.link`, unset on the car, set by `setprop` on the emulator farm where the
 * simulated vehicle arrives on a QEMU virtio port or a socket instead of the vendor UART.
 */
class AndroidOwnerGate(private val context: Context) : McuOwner.Gate {

    override fun eventcenterPresent(): Boolean = try {
        context.packageManager.getPackageInfo(CarService.BIND_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override fun ownerEnabled(): Boolean = readProp(PROP_CAR_OWNER) == ENABLED

    /** The MCU carrier; the vendor tty unless `riposte.mcu.link` says otherwise. */
    fun mcuLink(): McuLinkSpec = McuLinkSpec.parse(readProp(McuLinkSpec.PROP_MCU_LINK))

    /** The raw-bus carrier, or null when `riposte.canbus.link` is unset (the car: USB CANable). */
    fun canbusLink(): McuLinkSpec? = McuLinkSpec.parseOptional(readProp(McuLinkSpec.PROP_CANBUS_LINK))

    private fun readProp(name: String): String = try {
        ProcessBuilder(GETPROP, name).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        ""
    }

    companion object {
        const val PROP_CAR_OWNER = "ro.riposte.os.car_owner"
        private const val ENABLED = "1"
        private const val GETPROP = "/system/bin/getprop"
    }
}
