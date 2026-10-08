package com.ripostelabs.carlauncher.carlib

/**
 * OsPlan: which Riposte OS payload the unit takes, and when it may download, apply and reboot.
 * The plan in share carlauncher/os-ota.md, section 6, step 4:
 *
 *     pick      same milestone (0.2 stays 0.2: a new Android is a USB job with a wipe),
 *               same car_owner and bench flags, a later build, never a refused one
 *     download  unmetered link only (never the 200 MB tether budget), MIN_FREE_BYTES left after it
 *     apply     parked and settled as for an APK (OtaPlan.hold), MIN_FREE_BYTES for the snapshots
 *     reboot    only on the ACC off edge, once update_engine says UPDATED_NEED_REBOOT
 *
 * A build is named `<milestone>+<yyyymmdd>.vc<launcher versionCode>` (os/build.sh); a running
 * build named by hand (`--version`) is never updated over the air.
 */
class OsPlan(private val ota: OtaPlan = OtaPlan()) {

    enum class Hold { NONE, OFFLINE, METERED, LOW_SPACE, NO_CAR, REVERSE, ACC_OFF, CALL, CARPLAY, NOT_PARKED, SETTLING }

    /** The running image, from ro.riposte.os.version, ro.riposte.os.car_owner and ro.riposte.os.bench. */
    data class Running(val version: String, val carOwner: Boolean, val bench: Boolean)

    data class Version(val milestone: String, val day: Int, val code: Int) : Comparable<Version> {

        override fun compareTo(other: Version) = compareValuesBy(this, other, { it.day }, { it.code })

        companion object {
            private val FORM = Regex("""^(\d+\.\d+)\+(\d{8})\.vc(\d+)$""")

            fun parse(text: String): Version? {
                val m = FORM.matchEntire(text) ?: return null
                return Version(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
        }
    }

    /** The payload to take, newest first, or null when there is none for this unit. */
    fun pick(manifest: ReleaseManifest, running: Running, refused: Set<String>): ReleaseManifest.Os? {
        val have = Version.parse(running.version) ?: return null
        return manifest.os
            .filter { it.carOwner == running.carOwner && it.bench == running.bench && key(it) !in refused }
            .mapNotNull { row -> Version.parse(row.version)?.let { row to it } }
            .filter { (_, v) -> v.milestone == have.milestone && v > have }
            .maxByOrNull { (_, v) -> v }
            ?.first
    }

    /** Why a download must wait. [remainingBytes] is what is still to come of the payload. */
    fun downloadHold(link: DataBudget.Link, freeBytes: Long, remainingBytes: Long): Hold {
        if (link == DataBudget.Link.NONE) {
            return Hold.OFFLINE
        }
        if (link != DataBudget.Link.UNMETERED) {
            return Hold.METERED
        }
        if (freeBytes - remainingBytes < MIN_FREE_BYTES) {
            return Hold.LOW_SPACE
        }
        return Hold.NONE
    }

    /** Why an apply must wait (or be suspended). The COW files grow in /data while it runs. */
    fun applyHold(car: NoisePlan.Car?, accOnAtMs: Long?, nowMs: Long, freeBytes: Long): Hold {
        val held = when (ota.hold(car, accOnAtMs, nowMs)) {
            OtaPlan.Hold.NONE -> Hold.NONE
            OtaPlan.Hold.NO_CAR -> Hold.NO_CAR
            OtaPlan.Hold.REVERSE -> Hold.REVERSE
            OtaPlan.Hold.ACC_OFF -> Hold.ACC_OFF
            OtaPlan.Hold.CALL -> Hold.CALL
            OtaPlan.Hold.CARPLAY -> Hold.CARPLAY
            OtaPlan.Hold.NOT_PARKED -> Hold.NOT_PARKED
            OtaPlan.Hold.SETTLING -> Hold.SETTLING
        }
        if (held != Hold.NONE) {
            return held
        }
        if (freeBytes < MIN_FREE_BYTES) {
            return Hold.LOW_SPACE
        }
        return Hold.NONE
    }

    /** Reboot into the new slot when ACC goes off with the update staged, never while driving. */
    fun rebootDue(status: UpdateEngine.Status, before: NoisePlan.Acc?, now: NoisePlan.Acc?): Boolean =
        status == UpdateEngine.Status.UPDATED_NEED_REBOOT && before == NoisePlan.Acc.ON && now == NoisePlan.Acc.OFF

    companion object {
        /** /data must keep this much free (os-ota.md section 6): COW files, captures, the rest. */
        const val MIN_FREE_BYTES = 10L * 1024 * 1024 * 1024

        private const val KEY_SHA_CHARS = 16

        /** How a refused payload is remembered: `os:<version>:<sha256 prefix>`. */
        fun key(row: ReleaseManifest.Os) = "os:${row.version}:${row.sha256.take(KEY_SHA_CHARS)}"
    }
}
