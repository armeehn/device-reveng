package com.ripostelabs.carlauncher.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.ripostelabs.carlauncher.BuildConfig
import com.ripostelabs.carlauncher.carlib.DataBudget
import com.ripostelabs.carlauncher.carlib.Gear
import com.ripostelabs.carlauncher.carlib.NoisePlan
import com.ripostelabs.carlauncher.carlib.RawHttp
import com.ripostelabs.carlauncher.carlib.RoadNoiseFile
import com.ripostelabs.carlauncher.carlib.RootShell
import com.ripostelabs.carlauncher.carlib.SpeechGate
import com.ripostelabs.carlauncher.carlib.UplinkClient
import com.ripostelabs.carlauncher.carlib.UplinkQueue
import com.ripostelabs.carlauncher.data.UplinkPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/**
 * UplinkService: the car's half of the noise-reduction loop, with nobody touching anything.
 *
 *     drive ──▶ capture loop: cabin mic, 20 s windows in memory ──SpeechGate──▶ speech: zeroed
 *                                                                      └──────▶ noise: queue/
 *     online ─▶ upload loop:  queue/ + rotated log ring ──tailnet──▶ the owner's ingest server
 *
 * Capture runs only while [NoisePlan] says NONE (moving, ACC on, no call, no CarPlay, no other
 * recorder, not reversing, switch on, budget left), and a window is dropped whole the moment that
 * changes. Upload runs on any network, but tethered bytes stop at the monthly budget, and nothing
 * is sent while reversing or in a call. Files leave the flash only after the server confirms
 * their sha256 (UplinkQueue). The protocol and the link are in os/uplink/README.md.
 *
 * A foreground service of type microphone and dataSync: Android 14 lets a backgrounded app keep
 * the mic only that way, and the launcher is backgrounded whenever CarPlay or a map is in front.
 */
class UplinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: UplinkPrefs
    private lateinit var queue: UplinkQueue
    private lateinit var budget: DataBudget
    private val plan = NoisePlan()
    private val gate = SpeechGate()
    private var started = false

    @Volatile
    private var endpoint: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = UplinkPrefs.get(this)
        queue = UplinkQueue(File(filesDir, QUEUE_DIR))
        budget = DataBudget(prefs, limitBytes = { prefs.budgetBytes })
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Noise reduction uploads", NotificationManager.IMPORTANCE_MIN),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The microphone type may only be claimed with the permission granted.
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            (if (micGranted()) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(NOTIFICATION_ID, notification(), types)
        if (!started) {
            started = true
            if (!micGranted()) {
                scope.launch { grantMic() }
            }
            scope.launch { captureLoop() }
            scope.launch { uploadLoop() }
            // RAV4-270: release updates share the endpoint, the network type and the budget.
            OtaUpdater(this, budget, ::link) { endpoint()?.let { RawHttp.of(it) } }.start(scope)
            // RAV4-277: OS payloads ride the same manifest; off unless persist.riposte.os.ota=1.
            OsUpdater(this, ::link) { endpoint()?.let { RawHttp.of(it) } }.start(scope)
            Log.i(TAG, "uplink started (mic ${if (micGranted()) "granted" else "not granted"})")
        }
        return START_STICKY
    }

    /**
     * Default grants reach only a fresh /data (os/build.sh), so a unit updated in place lacks the
     * microphone. Root grants it, as Setup Doctor would; then the service claims the mic type.
     */
    private suspend fun grantMic() {
        RootShell.exec("pm grant $packageName ${Manifest.permission.RECORD_AUDIO}")
        if (!micGranted()) {
            return
        }
        withContext(Dispatchers.Main) {
            runCatching {
                startForeground(NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }.onFailure { Log.w(TAG, "mic type refused until the launcher is in front: ${it.message}") }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // --- capture ----------------------------------------------------------------------------

    private suspend fun CoroutineScope.captureLoop() {
        var lastAcc = NoisePlan.Acc.OFF
        while (isActive) {
            val car = UplinkSignals.car.value
            if (car == null) {
                delay(TICK_MS)
                continue
            }
            if (car.acc == NoisePlan.Acc.ON && lastAcc == NoisePlan.Acc.OFF) {
                plan.newDrive()
            }
            lastAcc = car.acc

            val hold = plan.hold(car.copy(otherRecorders = otherRecorders(NO_SESSION)), prefs.autoCapture,
                System.currentTimeMillis(), queue.queuedBytes())
            if (hold != NoisePlan.Hold.NONE) {
                delay(TICK_MS)
                continue
            }
            if (!micGranted()) {
                prefs.setState("Microphone permission missing")
                delay(IDLE_MS)
                continue
            }
            runCatching { captureWindow(car) }.onFailure { Log.w(TAG, "capture failed: ${it.message}") }
        }
    }

    /** One window into memory; written only if every check still holds and no speech is heard. */
    @SuppressLint("MissingPermission")
    private fun captureWindow(start: NoisePlan.Car) {
        val band = plan.band(start)
        val startedAt = Instant.now()
        val queued = queue.queuedBytes()
        val pcm = ShortArray(WINDOW_SAMPLES)
        val minBuf = AudioRecord.getMinBufferSize(RoadNoiseFile.SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RoadNoiseFile.SAMPLE_RATE)
                .setChannelMask(CHANNEL_IN).setEncoding(ENCODING).build())
            .setBufferSizeInBytes(maxOf(minBuf, READ_SAMPLES * BYTES_PER_SAMPLE * 4))
            .build()

        var filled = 0
        var abort: String? = null
        var speedSum = 0.0
        var speedReads = 0
        try {
            rec.startRecording()
            while (filled < pcm.size) {
                val n = rec.read(pcm, filled, minOf(READ_SAMPLES, pcm.size - filled))
                if (n <= 0) {
                    abort = "mic read $n"
                    break
                }
                filled += n

                // Re-check the car every read: a call, reverse or another recorder ends the window.
                val now = UplinkSignals.car.value ?: start
                val others = otherRecorders(rec.audioSessionId)
                val hold = plan.hold(now.copy(otherRecorders = others), prefs.autoCapture,
                    System.currentTimeMillis(), queued)
                if (hold != NoisePlan.Hold.NONE) {
                    abort = hold.name
                    break
                }
                if (silenced(rec.audioSessionId)) {
                    abort = "mic taken by another app"
                    break
                }
                speedSum += now.speedKmh
                speedReads++
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }

        if (abort != null) {
            pcm.fill(0)
            Log.i(TAG, "window dropped: $abort")
            return
        }

        if (RoadNoiseFile.isSilent(pcm)) {
            Log.i(TAG, "window dropped: the microphone delivered silence")
            return
        }
        val verdict = gate.judge(pcm)
        if (verdict.speech) {
            // Never written, never logged beyond the count: the samples are gone now.
            pcm.fill(0)
            plan.onSpeech(NoisePlan.WINDOW_S, System.currentTimeMillis())
            prefs.addDropped(NoisePlan.WINDOW_S)
            Log.i(TAG, "window held speech (${verdict.speechFrames} frames): discarded")
            return
        }

        val incoming = File(cacheDir, INCOMING_DIR).apply { mkdirs() }
        val wav = File(incoming, "${STAMP.format(startedAt.atZone(ZoneId.systemDefault()))}.wav")
        RoadNoiseFile.writeWav(wav, pcm, pcm.size)
        pcm.fill(0)
        val meta = RoadNoiseFile.sidecar(
            startedAt = startedAt, seconds = NoisePlan.WINDOW_S, band = band,
            speedKmh = if (speedReads > 0) speedSum / speedReads else start.speedKmh.toDouble(),
            gear = start.gear, fanLevel = start.fanLevel, ac = UplinkSignals.acOn,
            doorsOpen = UplinkSignals.doorsOpen, mediaActive = audio().isMusicActive, verdict = verdict,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        queue.add(UplinkClient.Kind.ROAD_NOISE, wav.name, wav, meta)
        plan.onKept(band, NoisePlan.WINDOW_S)
        prefs.addKept(NoisePlan.WINDOW_S)
        prefs.setQueued(queue.queuedBytes())
        Log.i(TAG, "kept a ${band.key} window")
    }

    private fun audio() = getSystemService(AudioManager::class.java)

    /** Recordings by anyone but us ([ours] = our session, or [NO_SESSION] when not recording). */
    private fun otherRecorders(ours: Int): Int =
        audio().activeRecordingConfigurations.count { it.clientAudioSessionId != ours }

    private fun silenced(ours: Int): Boolean =
        audio().activeRecordingConfigurations.any { it.clientAudioSessionId == ours && it.isClientSilenced }

    private fun micGranted() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // --- upload -----------------------------------------------------------------------------

    private suspend fun CoroutineScope.uploadLoop() {
        var lastWants = 0L
        var lastLogs = 0L
        while (isActive) {
            prefs.setQueued(queue.queuedBytes())
            val http = endpoint()?.let { RawHttp.of(it) }
            if (http == null) {
                prefs.setState("Not set up: no uplink endpoint on this unit")
                delay(ENDPOINT_RETRY_MS)
                continue
            }
            val link = link()
            if (link == DataBudget.Link.NONE) {
                prefs.setState("Waiting for Bluetooth tethering or Wi-Fi")
                delay(UPLOAD_TICK_MS)
                continue
            }

            val now = System.currentTimeMillis()
            if (now - lastLogs > LOGS_EVERY_MS) {
                runCatching { collectLogs() }.onFailure { Log.w(TAG, "log ring: ${it.message}") }
                lastLogs = now
            }
            if (now - lastWants > WANTS_EVERY_MS && fetchWants(http, link)) {
                lastWants = now
            }

            val result = queue.drain(UplinkClient(http), uploadGate(link)) { n -> budget.charge(link, n) }
            repeat(result.sent) { prefs.onUploaded(System.currentTimeMillis()) }
            prefs.setQueued(queue.queuedBytes())
            prefs.setState(describe(result, link))
            delay(if (result.stop == null) UPLOAD_TICK_MS else RETRY_MS)
        }
    }

    /** Asked before every chunk: the car's state and the budget may stop an upload mid-file. */
    private fun uploadGate(link: DataBudget.Link) = UplinkClient.Gate { bytes ->
        val car = UplinkSignals.car.value
        when {
            car?.gear == Gear.REVERSE -> "reversing"
            car?.call == NoisePlan.Call.ACTIVE -> "in a call"
            link() != link -> "network changed"
            !budget.allows(link, bytes) -> "tether budget used up"
            else -> null
        }
    }

    private fun describe(result: UplinkQueue.Drain, link: DataBudget.Link): String {
        val via = if (link == DataBudget.Link.METERED) "tether" else "Wi-Fi"
        return when (val stop = result.stop) {
            null -> if (result.sent > 0) "Sent ${result.sent} file(s) over $via" else "Up to date"
            is UplinkClient.Outcome.Paused -> "Paused: ${stop.reason}"
            is UplinkClient.Outcome.Failed -> "Retrying: ${stop.reason}"
            is UplinkClient.Outcome.Rejected -> "Server refused a file (${stop.status})"
            is UplinkClient.Outcome.Done -> "Up to date"
        }
    }

    /** The trainer's per-band targets, so the capture stops collecting what is no longer needed. */
    private fun fetchWants(http: RawHttp, link: DataBudget.Link): Boolean {
        if (!budget.allows(link, WANTS_BYTES)) {
            return false
        }
        val reply = runCatching { http.send("GET", "/v1/wants", emptyMap(), null) }.getOrNull() ?: return false
        budget.charge(link, WANTS_BYTES)
        if (reply.status != HTTP_OK) {
            return false
        }
        val bands = JSONObject(reply.body).optJSONObject("bands") ?: return true
        plan.onWants(bands.keys().asSequence().associateWith { key ->
            val b = bands.getJSONObject(key)
            NoisePlan.Need(targetS = if (b.has("target_s")) b.getDouble("target_s") else null, haveS = b.optDouble("have_s", 0.0))
        })
        return true
    }

    /**
     * Rotated files of the log ring (riposte-logring.sh), copied out through the root shell,
     * gzipped and queued. Only rotated files: the live one is still being written. Keyed by size
     * and mtime because rotation renames them; the queue dedupes by content as well.
     */
    private fun collectLogs() {
        val listing = RootShell.exec("stat -c '%n %s %Y' $LOG_RING/logcat.txt.* 2>/dev/null")
        if (!listing.ok) {
            return
        }
        val stage = File(cacheDir, LOG_STAGE).apply { mkdirs() }
        val label = RootShell.exec("ls -Zd ${RootShell.quote(stage.path)}").stdout.trim().split(' ').firstOrNull().orEmpty()
        for (line in listing.out) {
            val (path, size, mtime) = line.trim().split(' ').takeIf { it.size == 3 } ?: continue
            val key = "$size:$mtime"
            if (prefs.logSeen(key)) {
                continue
            }
            val raw = File(stage, "ring.txt")
            val copy = RootShell.exec(
                "cp ${RootShell.quote(path)} ${RootShell.quote(raw.path)} && " +
                    "chown ${Process.myUid()}:${Process.myUid()} ${RootShell.quote(raw.path)}" +
                    (if (label.isNotEmpty()) " && chcon $label ${RootShell.quote(raw.path)}" else ""),
            )
            if (!copy.ok || !raw.exists()) {
                continue
            }
            val stamp = STAMP.format(Instant.ofEpochSecond(mtime.toLong()).atZone(ZoneId.systemDefault()))
            val gz = File(stage, "logcat-$stamp.txt.gz")
            GZIPOutputStream(gz.outputStream()).use { out -> raw.inputStream().use { it.copyTo(out) } }
            raw.delete()
            queue.add(UplinkClient.Kind.DIAG, gz.name, gz, null)
            prefs.markLog(key)
        }
    }

    /**
     * The ingest URL, written once by os/uplink/enroll.sh into a root-only folder. The app's own
     * files folder is read first: only root or adb can write there, and it is how an emulator
     * without su is pointed at a server.
     */
    private fun endpoint(): String? {
        endpoint?.let { return it }
        val own = File(filesDir, ENDPOINT_NAME).takeIf { it.exists() }?.readText()?.trim()
        val found = own ?: RootShell.exec("cat $ENDPOINT_FILE").takeIf { it.ok }?.stdout?.trim()
        endpoint = found?.takeIf { it.startsWith("http://") }
        return endpoint
    }

    /** Home Wi-Fi is free; Bluetooth tethering and a metered hotspot spend the phone's plan. */
    private fun link(): DataBudget.Link {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return DataBudget.Link.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return DataBudget.Link.NONE
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            return DataBudget.Link.METERED
        }
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            DataBudget.Link.UNMETERED
        } else {
            DataBudget.Link.METERED
        }
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Improving noise reduction")
        .setContentText("Road noise only; speech is discarded on the unit")
        .setOngoing(true)
        .build()

    companion object {
        private const val TAG = "Uplink"
        private const val CHANNEL = "uplink"
        private const val NOTIFICATION_ID = 0x5550
        private const val QUEUE_DIR = "uplink/queue"
        private const val INCOMING_DIR = "uplink-new"
        private const val LOG_STAGE = "uplink-logs"
        private const val LOG_RING = "/data/riposte/log"
        private const val ENDPOINT_FILE = "/data/misc/riposte/uplink.endpoint"
        private const val ENDPOINT_NAME = "uplink.endpoint"
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val WINDOW_SAMPLES = (RoadNoiseFile.SAMPLE_RATE * NoisePlan.WINDOW_S).toInt()
        private const val READ_SAMPLES = RoadNoiseFile.SAMPLE_RATE / 10   // 100 ms per read
        private const val NO_SESSION = -1
        private const val TICK_MS = 1_000L
        private const val IDLE_MS = 60_000L
        private const val UPLOAD_TICK_MS = 30_000L
        private const val RETRY_MS = 120_000L
        private const val ENDPOINT_RETRY_MS = 300_000L
        private const val WANTS_EVERY_MS = 3_600_000L
        private const val LOGS_EVERY_MS = 6 * 3_600_000L
        private const val WANTS_BYTES = 2_000L
        private const val HTTP_OK = 200
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Start (or keep) the service; the launcher calls this whenever it comes to the front. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, UplinkService::class.java)) }
                .onFailure { Log.w(TAG, "could not start: ${it.message}") }
        }
    }
}
