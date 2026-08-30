package com.reveng.carlauncher.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.reveng.carlauncher.BuildConfig
import com.reveng.carlauncher.carlib.RootShell
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.7 — the auto-updater: pull the latest CI build from GitHub and install it over ourselves.
 *
 * The head unit's internet is the phone hotspot, so github.com is reachable whenever any
 * network is — unlike the home Gitea, which the car's tailnet ACL cannot see. CI mirrors every
 * tagged main build to github.com/armeehn/device-reveng as a release whose single asset is the
 * APK and whose notes carry its SHA-256 (launcher-ci.yml, "Mirror release to GitHub"). This
 * controller reads that feed, and when the release's versionCode (monotonic — it is the main
 * commit count) beats [BuildConfig.VERSION_CODE], downloads the APK, hashes it against the
 * published digest, and hands it to a root `pm install -r`.
 *
 * `pm install -r` — never uninstall+install: an uninstall wipes every DataStore irrecoverably
 * (see LauncherBackup's reason to exist) and drops the Magisk grant. The trade is that a
 * release signed with a *different* key is refused with INSTALL_FAILED_UPDATE_INCOMPATIBLE;
 * that refusal is surfaced verbatim rather than "fixed", because the fix (uninstall) is worse
 * than the failure. The install is staged through /data/local/tmp first — system_server can
 * refuse to read APKs off the emulated-storage FUSE mount, and "worked from adb, failed from
 * the app" is exactly the kind of ghost that costs an evening.
 *
 * The repo is private, so every request carries a GitHub token (fine-grained, contents:read is
 * enough). It is typed once into Settings ▸ Updates — or, kinder to whoever is holding the
 * car keyboard, pushed as a file and imported on the next check (see [tokenImportFile]).
 *
 * Sits where [RootTierController] sits and follows its rules: the UI reads StateFlows and
 * calls plain methods; nothing heavier than a DataStore read runs at construction.
 */
class UpdateController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val ds = appContext.updaterDataStore

    /** Persisted updater preferences (see [UpdaterSettings] for the default rationale). */
    val settings: StateFlow<UpdaterSettings> =
        ds.data.map { it.toUpdaterSettings() }
            .stateIn(scope, SharingStarted.Eagerly, UpdaterSettings())

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    /** Where the updater currently stands; drives the whole Settings ▸ Updates screen. */
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    fun setAutoCheck(enabled: Boolean) = scope.launch { ds.edit { it[AUTO_CHECK_KEY] = enabled } }
    fun setAutoInstall(enabled: Boolean) = scope.launch { ds.edit { it[AUTO_INSTALL_KEY] = enabled } }
    fun setToken(token: String) = scope.launch { ds.edit { it[TOKEN_KEY] = token.trim() } }

    /** Where an adb-pushed token is imported from (then deleted). Null without external storage. */
    fun tokenImportFile(): File? =
        appContext.getExternalFilesDir(null)?.let { File(File(it, UPDATES_DIR), TOKEN_IMPORT_NAME) }

    /**
     * The launch-time entry point, called once from MainActivity.onCreate. Self-gating: does
     * nothing unless auto-check is on, a token exists, and the last check is older than
     * [CHECK_INTERVAL_MS] — so a launcher restart (which every successful install causes)
     * doesn't turn into a check loop. With auto-install also on, a found update proceeds
     * straight to [installLatest]; the car is parked at launch in every scenario that matters,
     * which is why the auto path hangs off launch rather than a mid-drive timer.
     */
    fun autoCheckOnLaunch() {
        scope.launch {
            val prefs = ds.data.first().toUpdaterSettings()
            if (!prefs.autoCheck) return@launch
            val token = effectiveToken(prefs) ?: return@launch
            if (System.currentTimeMillis() - prefs.lastCheckMillis < CHECK_INTERVAL_MS) return@launch
            runCheck(token, thenInstall = prefs.autoInstall)
        }
    }

    /** Manual "Check now" from the Updates screen. */
    fun checkNow() {
        scope.launch {
            val token = effectiveToken(ds.data.first().toUpdaterSettings())
            if (token == null) {
                _status.value = UpdateStatus.Failed(
                    "No GitHub token. Add one below, or push it over adb.",
                )
                return@launch
            }
            runCheck(token, thenInstall = false)
        }
    }

    /** Download, verify and install the release [status] currently offers. */
    fun installLatest() {
        val available = _status.value as? UpdateStatus.Available ?: return
        scope.launch {
            val token = effectiveToken(ds.data.first().toUpdaterSettings()) ?: return@launch
            runInstall(available.release, token)
        }
    }

    // ---- The pipeline -------------------------------------------------------

    private suspend fun runCheck(token: String, thenInstall: Boolean) {
        // scope is the activity's lifecycleScope (main-confined), so this read-then-write
        // can't interleave with another pipeline entering.
        if (_status.value.busy) return
        _status.value = UpdateStatus.Checking
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val json = httpGet("$API_BASE/releases/latest", token, ACCEPT_JSON)
                UpdateFeed.parseLatest(json)
            }
        }
        ds.edit { it[LAST_CHECK_KEY] = System.currentTimeMillis() }

        outcome.fold(
            onSuccess = { release ->
                when {
                    release == null ->
                        _status.value = UpdateStatus.Failed(
                            "Latest release isn't a CI launcher build — nothing to compare against.",
                        )
                    UpdateFeed.isNewer(release, BuildConfig.VERSION_CODE) -> {
                        _status.value = UpdateStatus.Available(release)
                        if (thenInstall) runInstall(release, effectiveToken(ds.data.first().toUpdaterSettings()) ?: return)
                    }
                    else -> _status.value = UpdateStatus.UpToDate(release)
                }
            },
            onFailure = { e ->
                Log.w(TAG, "update check failed", e)
                _status.value = UpdateStatus.Failed(checkFailureMessage(e))
            },
        )
    }

    private suspend fun runInstall(release: UpdateFeed.Release, token: String) {
        if (_status.value.busy) return // double-tap on the install row
        _status.value = UpdateStatus.Downloading(release)

        val apk = withContext(Dispatchers.IO) {
            runCatching { downloadAndVerify(release, token) }
        }.getOrElse { e ->
            Log.w(TAG, "update download failed", e)
            _status.value = UpdateStatus.Failed(e.message ?: "Download failed.")
            return
        }

        _status.value = UpdateStatus.Installing(release)
        val result = withContext(Dispatchers.IO) {
            // Stage off the FUSE mount, install, clean up. One shell line: RootSession
            // refuses embedded newlines, and the `&&` keeps a failed copy from installing
            // a stale staging file from some earlier attempt.
            val src = RootShell.quote(apk.absolutePath)
            RootShell.exec("cp $src $STAGED_APK && pm install -r $STAGED_APK; rm -f $STAGED_APK")
        }

        // A successful install of ourselves kills this process before this line usually runs;
        // reaching it with ok=true just means the system was slow to swap us — say so.
        if (result.ok && result.out.any { it.contains("Success") }) {
            _status.value = UpdateStatus.Installed(release)
        } else {
            apk.delete()
            _status.value = UpdateStatus.Failed(installFailureMessage(result))
        }
    }

    /** Download the release APK into the updates dir and hash-check it. Throws on any mismatch. */
    private fun downloadAndVerify(release: UpdateFeed.Release, token: String): File {
        val base = appContext.getExternalFilesDir(null)
            ?: throw IOException("External storage unavailable — nowhere to stage the APK.")
        val dir = File(base, UPDATES_DIR).apply { mkdirs() }
        // One update on disk at a time; stale APKs are just flash-wear waiting to be reinstalled.
        dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.forEach { it.delete() }

        val dest = File(dir, release.apkName)
        val digest = MessageDigest.getInstance("SHA-256")
        openAsset(release.apkUrl, token).use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    output.write(buf, 0, n)
                }
            }
        }

        val expected = release.sha256
            ?: throw IOException("Release notes carry no SHA-256 — refusing an unverifiable APK.")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            dest.delete()
            throw IOException("Downloaded APK failed its SHA-256 check — bad transfer or bad asset.")
        }
        return dest
    }

    // ---- HTTP ---------------------------------------------------------------

    private fun connect(url: String, token: String, accept: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        return conn
    }

    private fun httpGet(url: String, token: String, accept: String): String {
        val conn = connect(url, token, accept)
        try {
            httpFail(conn.responseCode)?.let { throw IOException(it) }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Open the asset binary. GitHub answers the asset URL with a 302 to short-lived blob
     * storage, and that redirect must be followed WITHOUT the Authorization header — the target
     * is pre-signed, and presenting two credentials at once is rejected outright. Java's
     * auto-follow would happily re-send ours, so the hop is taken by hand.
     */
    private fun openAsset(url: String, token: String): java.io.InputStream {
        val first = connect(url, token, ACCEPT_BINARY)
        first.instanceFollowRedirects = false
        when (first.responseCode) {
            HttpURLConnection.HTTP_OK -> return first.inputStream
            HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_MOVED_PERM, HTTP_TEMP_REDIRECT -> {
                val location = first.getHeaderField("Location")
                    ?: throw IOException("GitHub redirected the asset without a Location.")
                first.disconnect()
                val second = URL(location).openConnection() as HttpURLConnection
                second.connectTimeout = CONNECT_TIMEOUT_MS
                second.readTimeout = READ_TIMEOUT_MS
                httpFail(second.responseCode)?.let { throw IOException(it) }
                return second.inputStream
            }
            else -> {
                val why = httpFail(first.responseCode) ?: "HTTP ${first.responseCode}"
                first.disconnect()
                throw IOException(why)
            }
        }
    }

    private fun httpFail(code: Int): String? = when (code) {
        in 200..299 -> null
        401 -> "GitHub rejected the token (401). Re-check it in the field below."
        403 -> "GitHub refused the request (403) — rate limit, or the token lacks access."
        404 -> "Release not found (404) — for a private repo this usually means the token can't see it."
        else -> "GitHub answered HTTP $code."
    }

    private fun checkFailureMessage(e: Throwable): String = when (e) {
        is IOException -> e.message ?: "Network unreachable — is the hotspot up?"
        else -> "Update check failed: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun installFailureMessage(result: RootShell.Result): String {
        val text = (result.err + result.out).joinToString(" ")
        return when {
            text.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
                "Install refused: the release is signed with a different key than this build. " +
                    "Don't uninstall to force it — that wipes all launcher data."
            text.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
                "Install refused: the release is older than this build."
            !RootShell.isRootAvailable() ->
                "Root unavailable — the updater needs a working su grant to run pm install."
            else -> "pm install failed: ${text.take(INSTALL_ERROR_SNIPPET_CHARS).ifBlank { "no output" }}"
        }
    }

    // ---- Token --------------------------------------------------------------

    /**
     * The stored token, or a one-shot import: a file pushed to
     * `Android/data/<applicationId>/files/updates/github-token.txt` is read, persisted and
     * deleted. A 90-character PAT on the car keyboard is a hazing ritual; `adb push` is not.
     */
    private suspend fun effectiveToken(prefs: UpdaterSettings): String? {
        if (prefs.token.isNotBlank()) return prefs.token
        val imported = withContext(Dispatchers.IO) {
            val f = tokenImportFile() ?: return@withContext null
            if (!f.isFile) return@withContext null
            val value = runCatching { f.readText().trim() }.getOrNull()
            f.delete()
            value?.takeIf { it.isNotBlank() }
        } ?: return null
        ds.edit { it[TOKEN_KEY] = imported }
        return imported
    }

    private fun Preferences.toUpdaterSettings() = UpdaterSettings(
        autoCheck = this[AUTO_CHECK_KEY] ?: true,
        autoInstall = this[AUTO_INSTALL_KEY] ?: false,
        token = this[TOKEN_KEY] ?: "",
        lastCheckMillis = this[LAST_CHECK_KEY] ?: 0L,
    )

    private companion object {
        const val TAG = "UpdateController"

        /** The GitHub mirror the CI release job publishes to. */
        const val API_BASE = "https://api.github.com/repos/armeehn/device-reveng"
        const val API_VERSION = "2022-11-28"
        const val ACCEPT_JSON = "application/vnd.github+json"
        const val ACCEPT_BINARY = "application/octet-stream"
        const val HTTP_TEMP_REDIRECT = 307

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val INSTALL_ERROR_SNIPPET_CHARS = 200

        /** Daily. The gap only has to beat "every launcher restart", not be clever. */
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        const val UPDATES_DIR = "updates"
        const val TOKEN_IMPORT_NAME = "github-token.txt"
        const val STAGED_APK = "/data/local/tmp/carlauncher-update.apk"

        val AUTO_CHECK_KEY = booleanPreferencesKey("auto_check")
        val AUTO_INSTALL_KEY = booleanPreferencesKey("auto_install")
        val TOKEN_KEY = stringPreferencesKey("github_token")
        val LAST_CHECK_KEY = longPreferencesKey("last_check_millis")
    }
}

/**
 * Persisted updater preferences.
 *
 * Auto-check defaults ON — it is read-only over the network and is the feature's reason to
 * exist. Auto-install defaults OFF, by the house rule LauncherSettingsTest pins as
 * `defaultsDoNotChangeTheCarWithoutBeingAsked`: a successful install restarts the HOME app,
 * and a launcher that blinks out on its own after an update, however briefly, is a bug unless
 * the driver opted into exactly that.
 */
data class UpdaterSettings(
    val autoCheck: Boolean = true,
    val autoInstall: Boolean = false,
    val token: String = "",
    val lastCheckMillis: Long = 0L,
)

/** The updater's state machine, in the order a successful run walks it. */
sealed interface UpdateStatus {
    /** Nothing checked yet this session. */
    data object Idle : UpdateStatus

    data object Checking : UpdateStatus

    /** Checked; the latest release is not newer than this build. */
    data class UpToDate(val release: UpdateFeed.Release) : UpdateStatus

    /** A newer build exists and can be installed. */
    data class Available(val release: UpdateFeed.Release) : UpdateStatus

    data class Downloading(val release: UpdateFeed.Release) : UpdateStatus

    data class Installing(val release: UpdateFeed.Release) : UpdateStatus

    /** Rare to observe: a successful self-install normally kills the process first. */
    data class Installed(val release: UpdateFeed.Release) : UpdateStatus

    data class Failed(val message: String) : UpdateStatus

    /** True while a check or install pipeline owns the state. */
    val busy: Boolean
        get() = this is Checking || this is Downloading || this is Installing
}

/** App-local DataStore (Preferences) for the updater; auto-included in LauncherBackup. */
private val Context.updaterDataStore: DataStore<Preferences> by preferencesDataStore(name = "updater")
