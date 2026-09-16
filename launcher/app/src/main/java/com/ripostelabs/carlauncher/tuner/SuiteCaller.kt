package com.ripostelabs.carlauncher.tuner

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process

/**
 * Who may transact with [TunerService]: our own signature, or any `com.ripostelabs.*` package
 * (the suite is signed by its own key, not the launcher's). Applied per call, not in onBind:
 * onBind runs as system_server, so the calling uid there is never the client.
 */
object SuiteCaller {
    const val PREFIX = "com.ripostelabs."

    /** Pure form for tests: [packages] are the caller's, [sameSignature] the PackageManager verdict. */
    fun allowed(callingUid: Int, myUid: Int, packages: List<String>, sameSignature: Boolean): Boolean {
        if (callingUid == myUid || sameSignature) {
            return true
        }

        return packages.any { it.startsWith(PREFIX) }
    }

    fun allowed(pm: PackageManager): Boolean {
        val uid = Binder.getCallingUid()
        val mine = Process.myUid()
        val same = pm.checkSignatures(mine, uid) == PackageManager.SIGNATURE_MATCH
        return allowed(uid, mine, pm.getPackagesForUid(uid)?.toList() ?: emptyList(), same)
    }
}
