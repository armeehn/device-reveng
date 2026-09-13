package com.ripostelabs.carlauncher.carlib

/**
 * AndroidAccSource — [McuSleepWake.AccSource] read from `sys.gotoSleep.state`.
 *
 * The vendor reads it with `SystemProperties.get` (AccObserver.java:23); that is a hidden API,
 * so `getprop` as in [AndroidOwnerGate]. "1" is ACC off (AccObserver.java:26), any other
 * non-empty value is on, and an empty read is no reading at all (:24).
 */
class AndroidAccSource : McuSleepWake.AccSource {

    override fun read(): McuSleepWake.Acc? {
        val value = try {
            ProcessBuilder(GETPROP, PROP_GOTO_SLEEP).redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            return null
        }

        if (value.isEmpty()) {
            return null
        }
        return if (value == ASLEEP) McuSleepWake.Acc.OFF else McuSleepWake.Acc.ON
    }

    companion object {
        const val PROP_GOTO_SLEEP = "sys.gotoSleep.state"
        private const val ASLEEP = "1"
        private const val GETPROP = "/system/bin/getprop"
    }
}
