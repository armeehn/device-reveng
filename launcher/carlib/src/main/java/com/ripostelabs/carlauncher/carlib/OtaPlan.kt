package com.ripostelabs.carlauncher.carlib

/**
 * OtaPlan: when the unit asks for releases, which ones it takes, and when it may install them.
 *
 *     check  daily, and once after each ACC on (when online)
 *     take   only packages already on the unit, never a build refused or rolled back before:
 *            launcher and car service on a higher versionCode (CI numbers every build),
 *            suite apps also on other bytes at the same versionCode (they all sit at 1)
 *     order  suite apps, then the car service, then the launcher (it restarts Home)
 *     install parked, ACC on for a minute, no call, no CarPlay session, never in reverse
 *
 * Downloads are not gated here: they run while driving, inside the tether budget, and pause
 * in reverse or a call like the uploads do (UplinkService).
 */
class OtaPlan {

    enum class Hold { NONE, NO_CAR, REVERSE, ACC_OFF, CALL, CARPLAY, NOT_PARKED, SETTLING }

    /** A package on the unit: its versionCode and the sha256 of its installed APK. */
    data class Installed(val versionCode: Long, val sha256: String)

    /**
     * Why an install must wait, or [Hold.NONE]. [accOnAtMs] is when ACC last came on; null when
     * the unit has not seen it happen, which counts as "just now".
     */
    fun hold(car: NoisePlan.Car?, accOnAtMs: Long?, nowMs: Long): Hold {
        if (car == null) {
            return Hold.NO_CAR
        }
        if (car.gear == Gear.REVERSE) {
            return Hold.REVERSE
        }
        if (car.acc != NoisePlan.Acc.ON) {
            return Hold.ACC_OFF
        }
        if (car.call == NoisePlan.Call.ACTIVE) {
            return Hold.CALL
        }
        if (car.carPlay == NoisePlan.Session.ACTIVE) {
            return Hold.CARPLAY
        }
        if (!parked(car)) {
            return Hold.NOT_PARKED
        }
        if (accOnAtMs == null || nowMs - accOnAtMs < SETTLE_MS) {
            return Hold.SETTLING
        }
        return Hold.NONE
    }

    /**
     * Parked: P when the CAN box reports the gear. Without it (gear null or unknown) a standing
     * car is the best evidence there is.
     */
    private fun parked(car: NoisePlan.Car): Boolean {
        if (car.speedKmh > 0) {
            return false
        }
        return car.gear == null || car.gear == Gear.UNKNOWN || car.gear == Gear.PARK
    }

    /** The updates to install, in install order. [installed] is keyed by package. */
    fun updates(
        manifest: ReleaseManifest,
        installed: Map<String, Installed>,
        refused: Set<String>,
    ): List<ReleaseManifest.App> = manifest.apps
        .filter { app ->
            val have = installed[app.pkg] ?: return@filter false
            newer(app, have) && key(app) !in refused
        }
        .sortedWith(compareBy({ it.role.ordinal }, { it.pkg }))

    private fun newer(app: ReleaseManifest.App, have: Installed): Boolean {
        if (app.versionCode != have.versionCode) {
            return app.versionCode > have.versionCode
        }
        return app.role == ReleaseManifest.Role.SUITE && app.sha256 != have.sha256
    }

    /** A check is due a day after the last one, or when ACC came on since the last one. */
    fun checkDue(nowMs: Long, lastCheckMs: Long, accOnAtMs: Long?): Boolean {
        if (nowMs - lastCheckMs >= CHECK_EVERY_MS) {
            return true
        }
        return accOnAtMs != null && accOnAtMs > lastCheckMs
    }

    companion object {
        const val SETTLE_MS = 60_000L
        const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

        private const val KEY_SHA_CHARS = 16

        /** How a refused or rolled-back build is remembered: `package:versionCode:sha256 prefix`. */
        fun key(app: ReleaseManifest.App) = "${app.pkg}:${app.versionCode}:${app.sha256.take(KEY_SHA_CHARS)}"
    }
}
