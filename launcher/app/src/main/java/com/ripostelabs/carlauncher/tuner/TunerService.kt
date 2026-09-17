package com.ripostelabs.carlauncher.tuner

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcel

/**
 * The bound face of [TunerHub] for the suite radio (RAV4-97). Every transaction is gated on
 * [SuiteCaller] before it reaches the hub; a stranger gets SecurityException on the binder.
 */
class TunerService : Service() {

    private val gated = object : android.os.Binder() {
        init { attachInterface(null, TunerHub.binder.interfaceDescriptor) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION && !SuiteCaller.allowed(packageManager)) {
                throw SecurityException("tuner: caller is not in the suite")
            }

            return TunerHub.binder.onTransact(code, data, reply, flags)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != ACTION) {
            return null
        }

        return gated
    }

    companion object {
        const val ACTION = "com.ripostelabs.carlauncher.tuner.ITuner"
    }
}
