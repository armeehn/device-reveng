package com.ripostelabs.carlauncher.service

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ripostelabs.carlauncher.BuildConfig
import com.ripostelabs.carlauncher.carlib.ApkFetch
import com.ripostelabs.carlauncher.carlib.DataBudget
import com.ripostelabs.carlauncher.carlib.Gear
import com.ripostelabs.carlauncher.carlib.NoisePlan
import com.ripostelabs.carlauncher.carlib.OtaPlan
import com.ripostelabs.carlauncher.carlib.OtaVerify
import com.ripostelabs.carlauncher.carlib.OtaWatch
import com.ripostelabs.carlauncher.carlib.RawHttp
import com.ripostelabs.carlauncher.carlib.ReleaseManifest
import com.ripostelabs.carlauncher.carlib.ReleasePin
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.carlib.UplinkClient
import com.ripostelabs.carlauncher.data.OtaPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * OtaUpdater: launcher, car service and suite updates from the estate, over the tailnet.
 *
 *     ACC on / daily ─▶ GET /v1/releases/manifest.json ─▶ OtaPlan.updates (newer, installed, not refused)
 *        ─▶ ApkFetch per app (resumable, tether budget shared with the uploads)
 *        ─▶ OtaVerify (size, sha256, pinned signer) + Android's own read of the archive
 *        ─▶ parked, a minute after ACC on, no call, no CarPlay, not in reverse (OtaPlan.hold)
 *        ─▶ root `pm install -r`: suite apps, then the car service, the launcher last
 *             launcher: OtaWatch script, detached: install, reopen Home, roll back on 2 crashes
 *
 * Runs inside UplinkService, which already holds the tailnet endpoint, the network type and the
 * monthly budget. zero's USB updater (os/car-update) stays the fallback for anything this
 * refuses, including a new signing key.
 */
class OtaUpdater(
    private val context: Context,
    private val budget: DataBudget,
    private val link: () -> DataBudget.Link,
    private val http: () -> RawHttp?,
) {
    private val prefs = OtaPrefs.get(context)
    private val plan = OtaPlan()
    private val dir = stateDir(context)
    private val apks = File(dir, APK_DIR)

    @Volatile
    private var accOnAtMs: Long? = null

    /** Installed APK sha256 by "path:lastUpdateTime", so 28 suite APKs are not hashed every tick. */
    private val shaCache = mutableMapOf<String, String>()

    fun start(scope: CoroutineScope) {
        scope.launch { trackAcc() }
        scope.launch { loop() }
    }

    /** When ACC last came on. The first snapshot after boot counts: the unit wakes with ACC. */
    private suspend fun trackAcc() {
        var last: NoisePlan.Acc? = null
        UplinkSignals.car.collect { car ->
            val acc = car?.acc ?: return@collect
            if (acc == NoisePlan.Acc.ON && last != NoisePlan.Acc.ON) {
                accOnAtMs = System.currentTimeMillis()
            }
            last = acc
        }
    }

    private suspend fun CoroutineScope.loop() {
        settleLauncher()
        while (isActive) {
            runCatching { tick() }.onFailure {
                Log.w(TAG, "ota tick failed", it)
                prefs.setState("Retrying: ${it.message}")
            }
            delay(TICK_MS)
        }
    }

    private fun tick() {
        val net = link()
        if (net == DataBudget.Link.NONE) {
            prefs.setState("Offline")
            return
        }
        val client = http() ?: run {
            prefs.setState("Not set up")
            return
        }

        val now = System.currentTimeMillis()
        if (plan.checkDue(now, prefs.lastCheckMs, accOnAtMs)) {
            check(client, net, now)
        }
        val manifest = ReleaseManifest.parse(prefs.manifest) ?: return
        val todo = plan.updates(manifest, installed(manifest), prefs.refused)
        prefs.setAvailable(todo.joinToString(", ") { "${short(it.pkg)} ${it.versionName} (${it.versionCode})" })
        if (todo.isEmpty()) {
            prefs.setState("Up to date")
            return
        }

        val fetch = ApkFetch(client, apks)
        for (app in todo) {
            if (!install(app, fetch, net)) {
                return
            }
        }
        prefs.setAvailable("")
        prefs.setState("Up to date")
    }

    private fun check(client: RawHttp, net: DataBudget.Link, now: Long) {
        if (!budget.allows(net, MANIFEST_BYTES)) {
            prefs.setState("Tether budget used up")
            return
        }
        val reply = client.send("GET", MANIFEST_PATH, emptyMap(), null)
        budget.charge(net, reply.body.length.toLong())
        if (reply.status != HTTP_OK || ReleaseManifest.parse(reply.body) == null) {
            prefs.setState("Server answered ${reply.status}")
            return
        }
        prefs.onChecked(now, reply.body)
    }

    /**
     * One app through download, verify and install. False stops the run for this tick: an app
     * still downloading or waiting for a parked car holds the ones after it, so the launcher
     * always goes last.
     */
    private fun install(app: ReleaseManifest.App, fetch: ApkFetch, net: DataBudget.Link): Boolean {
        val key = OtaPlan.key(app)
        if (!budget.allows(net, fetch.remaining(app))) {
            prefs.setState("Waiting for Wi-Fi or next month's budget: ${short(app.pkg)}")
            return false
        }
        val file = when (val r = fetch.fetch(app, downloadGate(net)) { budget.charge(net, it) }) {
            is ApkFetch.Result.Done -> r.file
            is ApkFetch.Result.Paused -> return pause("Download paused: ${r.reason}")
            is ApkFetch.Result.Failed -> return pause("Download failed: ${r.reason}")
            ApkFetch.Result.Corrupt -> return pause("Download of ${short(app.pkg)} failed its sha256")
        }

        val refusal = refusal(app, file)
        if (refusal != null) {
            file.delete()
            prefs.refuse(key)
            prefs.setResult("Refused ${short(app.pkg)} ${app.versionCode}: $refusal")
            Log.w(TAG, "refused $key: $refusal")
            return true
        }

        val hold = plan.hold(UplinkSignals.car.value, accOnAtMs, System.currentTimeMillis())
        if (hold != OtaPlan.Hold.NONE) {
            return pause("Ready, waiting to install: ${hold.describe()}")
        }
        if (app.role == ReleaseManifest.Role.LAUNCHER) {
            replaceLauncher(app, file)
            return false
        }
        return installApp(app, file, key)
    }

    private fun pause(text: String): Boolean {
        prefs.setState(text)
        return false
    }

    /** Downloads run while driving, but stop in reverse, in a call, or past the budget. */
    private fun downloadGate(net: DataBudget.Link) = UplinkClient.Gate { bytes ->
        val car = UplinkSignals.car.value
        when {
            car?.gear == Gear.REVERSE -> "reversing"
            car?.call == NoisePlan.Call.ACTIVE -> "in a call"
            link() != net -> "network changed"
            !budget.allows(net, bytes) -> "tether budget used up"
            else -> null
        }
    }

    /**
     * Why [file] must not be installed, or null. The pin check reads the certificate the file
     * names; Android's archive reader then confirms package, version and signer independently,
     * and `pm install` verifies the signature itself.
     */
    private fun refusal(app: ReleaseManifest.App, file: File): String? {
        val verdict = OtaVerify.check(app, file)
        if (verdict != OtaVerify.Verdict.OK) {
            return verdict.name.lowercase().replace('_', ' ')
        }
        val info = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "not a readable APK"
        if (info.packageName != app.pkg || info.longVersionCode != app.versionCode) {
            return "file is ${info.packageName} ${info.longVersionCode}"
        }
        val signers = info.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }
        if (!signers.any { ReleasePin.RELEASE.accepts(app.role, it) }) {
            return "Android reads another signer"
        }
        return null
    }

    private fun installApp(app: ReleaseManifest.App, file: File, key: String): Boolean {
        prefs.setState("Installing ${short(app.pkg)} ${app.versionCode}")
        val r = RootShell.exec("cp ${RootShell.quote(file.path)} $STAGED && pm install -r $STAGED; rm -f $STAGED")
        if (r.out.any { it.contains("Success") }) {
            file.delete()
            prefs.setResult("Installed ${short(app.pkg)} ${app.versionName} (${app.versionCode})")
            Log.i(TAG, "installed $key")
            return true
        }
        val text = (r.err + r.out).joinToString(" ").take(ERROR_CHARS)
            .ifBlank { if (RootShell.isRootAvailable()) "no output" else "root shell unavailable" }
        Log.w(TAG, "install $key failed: $text")
        if (prefs.failed(key) >= MAX_FAILURES) {
            file.delete()
            prefs.refuse(key)
        }
        prefs.setResult("Install of ${short(app.pkg)} ${app.versionCode} failed: $text")
        return pause("Install failed, will retry: ${short(app.pkg)}")
    }

    /**
     * The launcher replaces itself. Keep the running APK for the rollback, stage the new one where
     * system_server can read it, write the watch script and start it detached; `pm install` then
     * ends this process and the script reopens Home.
     */
    private fun replaceLauncher(app: ReleaseManifest.App, file: File) {
        val rollback = File(dir, ROLLBACK_DIR).apply { mkdirs() }
        rollback.listFiles()?.forEach { it.delete() }
        val kept = File(rollback, "${context.packageName}-${BuildConfig.VERSION_CODE}.apk")
        File(context.applicationInfo.sourceDir).copyTo(kept, overwrite = true)

        val staged = RootShell.exec("cp ${RootShell.quote(file.path)} $STAGED_LAUNCHER")
        if (!staged.ok) {
            prefs.setState("Root shell unavailable: cannot install the launcher")
            return
        }
        val script = File(dir, "watch-${app.versionCode}.sh")
        script.writeText(OtaWatch.script(OtaWatch.Plan(
            pkg = context.packageName,
            versionCode = app.versionCode,
            newApk = STAGED_LAUNCHER,
            oldApk = kept.path,
            stateDir = dir.path,
        )))
        OtaWatch.resultFile(dir, app.versionCode).delete()
        prefs.setPendingLauncher(app.versionCode, OtaPlan.key(app))
        prefs.setState("Installing launcher ${app.versionName} (${app.versionCode}); Home will reopen")
        Log.i(TAG, "replacing the launcher with ${app.versionCode}")
        RootShell.exec("setsid sh ${RootShell.quote(script.path)} > ${RootShell.quote(File(dir, WATCH_LOG).path)} 2>&1 < /dev/null &")
    }

    /** What the watch script did with the launcher handed to it, read by whichever launcher runs now. */
    private fun settleLauncher() {
        val vc = prefs.pendingLauncher
        if (vc == 0L) {
            return
        }
        val result = OtaWatch.result(dir, vc)
        if (result == OtaWatch.Result.NONE) {
            return
        }
        val key = prefs.pendingKey
        val text = when (result) {
            OtaWatch.Result.OK -> "Launcher $vc installed and started"
            OtaWatch.Result.UNCONFIRMED -> "Launcher $vc installed"
            OtaWatch.Result.INSTALL_FAILED -> "Launcher $vc did not install"
            OtaWatch.Result.ROLLED_BACK -> "Launcher $vc crashed on start; rolled back"
            OtaWatch.Result.ROLLBACK_FAILED -> "Launcher $vc crashed on start; rollback failed"
            OtaWatch.Result.NONE -> return
        }
        if (result in REFUSING) {
            prefs.refuse(key)
        }
        prefs.setResult(text)
        prefs.setPendingLauncher(0, "")
        File(dir, ROLLBACK_DIR).takeIf { result == OtaWatch.Result.OK }?.listFiles()?.forEach { it.delete() }
    }

    /** What the unit has of each package in [manifest]; sha256 read once per installed APK. */
    private fun installed(manifest: ReleaseManifest): Map<String, OtaPlan.Installed> = manifest.apps.mapNotNull { app ->
        val info = runCatching { context.packageManager.getPackageInfo(app.pkg, 0) }.getOrNull() ?: return@mapNotNull null
        val apk = File(info.applicationInfo?.sourceDir ?: return@mapNotNull null)
        val stamp = "${apk.path}:${info.lastUpdateTime}"
        val sha = shaCache[stamp] ?: runCatching { OtaVerify.sha256(apk) }.getOrNull()?.also { shaCache[stamp] = it }
            ?: return@mapNotNull null
        app.pkg to OtaPlan.Installed(info.longVersionCode, sha)
    }.toMap()

    private fun OtaPlan.Hold.describe(): String = when (this) {
        OtaPlan.Hold.NONE -> "ready"
        OtaPlan.Hold.NO_CAR -> "no car state yet"
        OtaPlan.Hold.REVERSE -> "reversing"
        OtaPlan.Hold.ACC_OFF -> "ACC off"
        OtaPlan.Hold.CALL -> "in a call"
        OtaPlan.Hold.CARPLAY -> "CarPlay session"
        OtaPlan.Hold.NOT_PARKED -> "not parked"
        OtaPlan.Hold.SETTLING -> "first minute after ACC on"
    }

    private fun short(pkg: String) = pkg.substringAfterLast('.')

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "Ota"
        private const val OTA_DIR = "ota"
        private const val APK_DIR = "apk"
        private const val ROLLBACK_DIR = "rollback"
        private const val WATCH_LOG = "watch.log"
        private const val MANIFEST_PATH = "/v1/releases/manifest.json"
        private const val MANIFEST_BYTES = 20_000L
        private const val STAGED = "/data/local/tmp/riposte-ota.apk"
        private const val STAGED_LAUNCHER = "/data/local/tmp/riposte-ota-launcher.apk"
        private const val TICK_MS = 60_000L
        private const val HTTP_OK = 200
        private const val MAX_FAILURES = 3
        private const val ERROR_CHARS = 200
        private val REFUSING = setOf(
            OtaWatch.Result.INSTALL_FAILED, OtaWatch.Result.ROLLED_BACK, OtaWatch.Result.ROLLBACK_FAILED,
        )

        /** How long the launcher must run before the watch script calls it healthy. */
        const val HEALTHY_AFTER_MS = 20_000L

        private fun stateDir(context: Context) = File(context.filesDir, OTA_DIR).apply { mkdirs() }

        /** Called once the launcher has been up for [HEALTHY_AFTER_MS]: tells the watch script "keep me". */
        fun markHealthy(context: Context) {
            runCatching { OtaWatch.healthyMarker(stateDir(context), BuildConfig.VERSION_CODE.toLong()).writeText("ok") }
        }
    }
}
