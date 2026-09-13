package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.content.pm.PackageManager

/**
 * AndroidOwnerGate — [McuOwner.Gate] answered from the running system.
 *
 * eventcenter present = the vendor owns the port, full stop. The OS flag is
 * `ro.riposte.os.car_owner`, written by `os/build.sh --car-owner` (and by profile gsi) into
 * build.prop; read with `getprop` because `SystemProperties` is a hidden API.
 */
class AndroidOwnerGate(private val context: Context) : McuOwner.Gate {

    override fun eventcenterPresent(): Boolean = try {
        context.packageManager.getPackageInfo(CarService.BIND_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override fun ownerEnabled(): Boolean = readProp(PROP_CAR_OWNER) == ENABLED

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
