package com.ripostelabs.carlauncher.carlib

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ApkFetch: one release APK onto the unit's flash, resumable, chunk by chunk.
 *
 *     <dir>/<package>-<versionCode>.part   bytes so far; the next Range starts at its length
 *     <dir>/<package>-<versionCode>.apk    complete and matching the manifest's sha256
 *
 * Before every chunk the caller's [UplinkClient.Gate] may stop it (reverse, a call, the network
 * changed, the tether budget is spent); the partial stays for next time. A finished file whose
 * sha256 is wrong is deleted whole, so a bad transfer costs one download, not a bad install.
 * Other versions of the same package are cleared, so flash holds one candidate per app.
 */
class ApkFetch(
    private val http: Http,
    private val dir: File,
    private val chunkBytes: Int = CHUNK_BYTES,
) {

    /** One ranged GET of `/v1/releases/<path>`, bytes [from, to]. Throws on a dead link. */
    fun interface Http {
        @Throws(IOException::class)
        fun range(path: String, from: Long, to: Long): Reply
    }

    class Reply(val status: Int, val body: ByteArray)

    sealed interface Result {
        data class Done(val file: File) : Result
        data class Paused(val reason: String) : Result
        data class Failed(val reason: String) : Result

        /** The whole file arrived and its sha256 is not the manifest's. Nothing was kept. */
        data object Corrupt : Result
    }

    fun fetch(app: ReleaseManifest.App, gate: UplinkClient.Gate, onBytes: (Long) -> Unit): Result {
        dir.mkdirs()
        clearOthers(app)
        val done = apk(app)
        if (done.length() == app.size && OtaVerify.sha256(done) == app.sha256) {
            return Result.Done(done)
        }
        done.delete()

        val part = part(app)
        if (part.length() > app.size) {
            part.delete()
        }
        try {
            pull(app, part, gate, onBytes)?.let { return it }
        } catch (e: IOException) {
            return Result.Failed("link: ${e.message}")
        }

        if (OtaVerify.sha256(part) != app.sha256) {
            part.delete()
            return Result.Corrupt
        }
        if (!part.renameTo(done)) {
            return Result.Failed("could not keep ${done.name}")
        }
        return Result.Done(done)
    }

    /** Bytes of [app] already on flash. */
    fun partBytes(app: ReleaseManifest.App): Long = part(app).length()

    /** Bytes still to fetch for [app]: the budget is asked for this before a download starts. */
    fun remaining(app: ReleaseManifest.App): Long {
        if (apk(app).length() == app.size) {
            return 0
        }
        return (app.size - partBytes(app)).coerceAtLeast(0)
    }

    /** Chunks from the partial's length to the end. Null when every byte is in. */
    private fun pull(app: ReleaseManifest.App, part: File, gate: UplinkClient.Gate, onBytes: (Long) -> Unit): Result? {
        var offset = part.length()
        FileOutputStream(part, true).use { out ->
            while (offset < app.size) {
                val n = minOf(chunkBytes.toLong(), app.size - offset)
                gate.pause(n)?.let { return Result.Paused(it) }

                val reply = http.range(app.path, offset, offset + n - 1)
                onBytes(reply.body.size.toLong())
                val whole = reply.status == HTTP_OK && offset == 0L && reply.body.size.toLong() == app.size
                if (reply.status != HTTP_PARTIAL && !whole) {
                    return Result.Failed("server answered ${reply.status}")
                }
                val take = if (whole) app.size else n
                if (reply.body.size.toLong() != take) {
                    return Result.Failed("short chunk: ${reply.body.size} of $take bytes")
                }
                out.write(reply.body)
                out.flush()
                offset += take
            }
        }
        return null
    }

    private fun clearOthers(app: ReleaseManifest.App) {
        val mine = Regex("^" + Regex.escape(app.pkg) + "-(\\d+)\\.(apk|part)$")
        dir.listFiles()?.forEach { f ->
            val m = mine.matchEntire(f.name) ?: return@forEach
            if (m.groupValues[1].toLong() != app.versionCode) {
                f.delete()
            }
        }
    }

    private fun apk(app: ReleaseManifest.App) = File(dir, "${app.pkg}-${app.versionCode}.apk")

    private fun part(app: ReleaseManifest.App) = File(dir, "${app.pkg}-${app.versionCode}.part")

    companion object {
        const val CHUNK_BYTES = 256 * 1024
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
    }
}
