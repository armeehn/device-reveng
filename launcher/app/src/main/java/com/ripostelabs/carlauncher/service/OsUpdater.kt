package com.ripostelabs.carlauncher.service

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.ripostelabs.carlauncher.carlib.ApkFetch
import com.ripostelabs.carlauncher.carlib.DataBudget
import com.ripostelabs.carlauncher.carlib.Gear
import com.ripostelabs.carlauncher.carlib.NoisePlan
import com.ripostelabs.carlauncher.carlib.OsPlan
import com.ripostelabs.carlauncher.carlib.RawHttp
import com.ripostelabs.carlauncher.carlib.ReleaseManifest
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.carlib.UpdateEngine
import com.ripostelabs.carlauncher.carlib.UplinkClient
import com.ripostelabs.carlauncher.data.OsPrefs
import com.ripostelabs.carlauncher.data.OtaPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * OsUpdater: Riposte OS payloads from the estate into the other slot (share carlauncher/os-ota.md).
 *
 *     manifest (OtaUpdater fetched it) ─▶ OsPlan.pick: same milestone and flags, later build
 *        ─▶ ApkFetch, unmetered link only, 10 GB left ─▶ sha256 against the manifest
 *        ─▶ parked and settled ─▶ root: mv into /data/ota_package, update_engine_client --update
 *        ─▶ car moves: --suspend; parked again: --resume
 *        ─▶ UPDATED_NEED_REBOOT ─▶ ACC off edge ─▶ reboot into the new slot
 *        ─▶ next launcher: running version == pending? done : refused (the slot fell back)
 *
 * update_engine checks the payload's signature against the running image's otacerts and writes
 * only the other slot, so the running system is never touched. Off unless the image (or adb on
 * the bench) sets persist.riposte.os.ota=1: the first apply is a supervised bench test (os-ota.md
 * section 7). Only an image with riposte-bootcheck may take one: it marks the new slot good
 * only once the launcher stays up, else switches back. The marker it reads is written here.
 */
class OsUpdater(
    private val context: Context,
    private val link: () -> DataBudget.Link,
    private val http: () -> RawHttp?,
) {
    private val prefs = OsPrefs.get(context)
    private val ota = OtaPrefs.get(context)
    private val plan = OsPlan()
    private val dir = File(context.filesDir, OS_DIR)

    @Volatile
    private var accOnAtMs: Long? = null

    fun start(scope: CoroutineScope) {
        scope.launch { trackAcc() }
        scope.launch { loop() }
    }

    /** ACC on starts the settle clock; the ACC off edge is the only moment to reboot. */
    private suspend fun trackAcc() {
        var last: NoisePlan.Acc? = null
        UplinkSignals.car.collect { car ->
            val acc = car?.acc ?: return@collect
            if (acc == NoisePlan.Acc.ON && last != NoisePlan.Acc.ON) {
                accOnAtMs = System.currentTimeMillis()
            }
            val offEdge = last == NoisePlan.Acc.ON && acc == NoisePlan.Acc.OFF
            if (offEdge && prefs.phase == OsPrefs.Phase.APPLYING && plan.rebootDue(engine()?.status ?: UpdateEngine.Status.UNKNOWN, last, acc)) {
                reboot()
            }
            last = acc
        }
    }

    private suspend fun CoroutineScope.loop() {
        while (isActive) {
            runCatching { tick() }.onFailure {
                Log.w(TAG, "os tick failed", it)
                prefs.setState("Retrying: ${it.message}")
            }
            delay(TICK_MS)
        }
    }

    private fun tick() {
        if (prop(PROP_ENABLED) != ENABLED) {
            prefs.setState("Off (persist.riposte.os.ota is not 1)")
            return
        }
        val running = OsPlan.Running(prop(PROP_VERSION), prop(PROP_CAR_OWNER) == ENABLED, prop(PROP_BENCH) == ENABLED)
        when (prefs.phase) {
            OsPrefs.Phase.REBOOTING -> return settle(running)
            OsPrefs.Phase.APPLYING, OsPrefs.Phase.SUSPENDED -> return follow()
            OsPrefs.Phase.IDLE -> Unit
        }

        val manifest = ReleaseManifest.parse(ota.manifest) ?: return prefs.setState("No manifest yet")
        val row = plan.pick(manifest, running, prefs.refused)
            ?: return prefs.setState("Up to date (${running.version.ifEmpty { "unknown build" }})")
        val client = http() ?: return prefs.setState("Not set up")

        val fetch = ApkFetch(client, dir)
        val item = ApkFetch.Item(GROUP, row.version, PAYLOAD_EXT, row.path, row.sha256, row.size)
        val held = plan.downloadHold(link(), freeBytes(), fetch.remaining(item))
        if (held != OsPlan.Hold.NONE) {
            return prefs.setState("${row.version} available, waiting: ${held.describe()}")
        }
        prefs.setState("Downloading ${row.version}")
        val file = when (val r = fetch.fetch(item, downloadGate()) {}) {
            is ApkFetch.Result.Done -> r.file
            is ApkFetch.Result.Paused -> return prefs.setState("Download paused: ${r.reason}")
            is ApkFetch.Result.Failed -> return prefs.setState("Download failed: ${r.reason}")
            ApkFetch.Result.Corrupt -> return prefs.setState("Download of ${row.version} failed its sha256")
        }
        apply(row, file)
    }

    /** Parked and settled: move the payload where update_engine may read it, and start. */
    private fun apply(row: ReleaseManifest.Os, file: File) {
        val held = plan.applyHold(UplinkSignals.car.value, accOnAtMs, System.currentTimeMillis(), freeBytes())
        if (held != OsPlan.Hold.NONE) {
            return prefs.setState("${row.version} downloaded, waiting to apply: ${held.describe()}")
        }
        val cmd = UpdateEngine.apply(STAGED, row.size, row.headers) ?: run {
            prefs.refuse(OsPlan.key(row))
            return prefs.setResult("Refused ${row.version}: headers not usable")
        }
        val moved = RootShell.exec("mkdir -p ${UpdateEngine.PACKAGE_DIR} && mv ${RootShell.quote(file.path)} $STAGED")
        if (!moved.ok) {
            return prefs.setState("Root shell unavailable: cannot stage ${row.version}")
        }
        val started = RootShell.exec(cmd)
        if (!started.ok) {
            return fail(OsPlan.key(row), "${row.version} did not start: ${(started.err + started.out).joinToString(" ").take(ERROR_CHARS)}")
        }
        prefs.setPhase(OsPrefs.Phase.APPLYING, row.version, OsPlan.key(row))
        prefs.setState("Applying ${row.version}")
        Log.i(TAG, "applying ${row.version}")
    }

    /** An apply in flight: suspend while the car moves, resume when parked, note when it is staged. */
    private fun follow() {
        val p = engine() ?: return prefs.setState("update_engine not answering")
        val version = prefs.pendingVersion
        when (p.status) {
            UpdateEngine.Status.UPDATED_NEED_REBOOT -> prefs.setState("$version ready; restarts at the next ACC off")
            UpdateEngine.Status.IDLE -> fail(prefs.pendingKey, "$version was not applied (update_engine idle)")
            else -> pace(version, p)
        }
    }

    private fun pace(version: String, p: UpdateEngine.Progress) {
        val held = plan.applyHold(UplinkSignals.car.value, accOnAtMs, System.currentTimeMillis(), freeBytes())
        val pct = (p.fraction * PERCENT).toInt()
        if (held != OsPlan.Hold.NONE && prefs.phase == OsPrefs.Phase.APPLYING) {
            RootShell.exec(UpdateEngine.SUSPEND)
            prefs.setPhase(OsPrefs.Phase.SUSPENDED)
        }
        if (held == OsPlan.Hold.NONE && prefs.phase == OsPrefs.Phase.SUSPENDED) {
            RootShell.exec(UpdateEngine.RESUME)
            prefs.setPhase(OsPrefs.Phase.APPLYING)
        }
        val paused = if (prefs.phase == OsPrefs.Phase.SUSPENDED) ", paused: ${held.describe()}" else ""
        prefs.setState("Applying $version: ${p.status.name.lowercase()} $pct%$paused")
    }

    private fun reboot() {
        prefs.setPhase(OsPrefs.Phase.REBOOTING)
        prefs.setState("Restarting into ${prefs.pendingVersion}")
        Log.i(TAG, "ACC off with ${prefs.pendingVersion} staged: reboot")
        // The marker tells riposte-bootcheck this boot is the update's first; without it the
        // new slot would be marked good unchecked, so no marker, no reboot.
        val r = RootShell.exec("mkdir -p $MARK_DIR && echo \$((1 - \$(bootctl get-current-slot))) > $MARK && reboot")
        if (!r.ok) {
            prefs.setPhase(OsPrefs.Phase.APPLYING)
            prefs.setState("Could not mark the update for riposte-bootcheck; not restarting")
        }
    }

    /** After the reboot: the new slot runs, or the bootloader fell back to the old one. */
    private fun settle(running: OsPlan.Running) {
        val version = prefs.pendingVersion
        if (running.version == version) {
            RootShell.exec("rm -f $STAGED")
            prefs.setResult("Riposte OS $version installed")
        } else {
            prefs.refuse(prefs.pendingKey)
            prefs.setResult("Riposte OS $version did not stay; running ${running.version}")
        }
        prefs.clear()
    }

    private fun fail(key: String, text: String) {
        RootShell.exec("rm -f $STAGED")
        prefs.refuse(key)
        prefs.setResult(text)
        prefs.clear()
        Log.w(TAG, text)
    }

    private fun engine(): UpdateEngine.Progress? = UpdateEngine.status(RootShell.exec(UpdateEngine.STATUS).stdout)

    /** The payload comes only over an unmetered link, and stops in reverse or a call. */
    private fun downloadGate() = UplinkClient.Gate { _ ->
        val car = UplinkSignals.car.value
        when {
            car?.gear == Gear.REVERSE -> "reversing"
            car?.call == NoisePlan.Call.ACTIVE -> "in a call"
            link() != DataBudget.Link.UNMETERED -> "left Wi-Fi"
            else -> null
        }
    }

    private fun freeBytes(): Long = StatFs(context.filesDir.path).availableBytes

    private fun prop(name: String): String = try {
        ProcessBuilder(GETPROP, name).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        ""
    }

    private fun OsPlan.Hold.describe(): String = when (this) {
        OsPlan.Hold.NONE -> "ready"
        OsPlan.Hold.OFFLINE -> "offline"
        OsPlan.Hold.METERED -> "Wi-Fi only"
        OsPlan.Hold.LOW_SPACE -> "under 10 GB free"
        OsPlan.Hold.NO_CAR -> "no car state yet"
        OsPlan.Hold.REVERSE -> "reversing"
        OsPlan.Hold.ACC_OFF -> "ACC off"
        OsPlan.Hold.CALL -> "in a call"
        OsPlan.Hold.CARPLAY -> "CarPlay session"
        OsPlan.Hold.NOT_PARKED -> "not parked"
        OsPlan.Hold.SETTLING -> "first minute after ACC on"
    }

    companion object {
        private const val TAG = "OsOta"
        private const val OS_DIR = "os-ota"
        private const val GROUP = "riposte-os"
        private const val PAYLOAD_EXT = "bin"
        private const val STAGED = "${UpdateEngine.PACKAGE_DIR}/payload.bin"
        private const val MARK_DIR = "/data/riposte"
        private const val MARK = "$MARK_DIR/ota-pending"   // os/overlay/system/bin/riposte-bootcheck.sh
        private const val TICK_MS = 60_000L
        private const val PERCENT = 100
        private const val ERROR_CHARS = 200
        private const val GETPROP = "/system/bin/getprop"
        private const val ENABLED = "1"
        private const val PROP_ENABLED = "persist.riposte.os.ota"
        private const val PROP_VERSION = "ro.riposte.os.version"
        private const val PROP_CAR_OWNER = "ro.riposte.os.car_owner"
        private const val PROP_BENCH = "ro.riposte.os.bench"
    }
}
