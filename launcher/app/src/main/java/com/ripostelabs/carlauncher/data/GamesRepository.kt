package com.ripostelabs.carlauncher.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ripostelabs.carlauncher.carlib.GameApp
import com.ripostelabs.carlauncher.carlib.GameApps

/**
 * Finds and starts the emulator frontends on this unit.
 *
 * The catalogue and the matching rules live in [GameApps], which is testable without a device.
 * This is only the part that has to touch the package manager.
 */
object GamesRepository {

    /** Frontends actually present, in catalogue order. */
    fun installed(context: Context): List<GameApp> {
        val pm = context.packageManager
        val present = GameApps.KNOWN.filter {
            runCatching { pm.getLaunchIntentForPackage(it.packageName) }.getOrNull() != null
        }

        return present
    }

    /**
     * Launch [packageName]. Returns false when it has no launch intent, which is the case for a
     * package that is installed but disabled — worth reporting rather than failing silently.
     */
    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w("GamesRepository", "launch $packageName failed", it) }
            .isSuccess
    }
}
