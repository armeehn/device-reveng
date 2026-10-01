package com.ripostelabs.carlauncher.carlib

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * UplinkClient: one file to the owner's ingest server, resumable, chunk by chunk.
 *
 * The protocol (os/uplink/README.md) addresses an upload by the file's sha256, so the car needs no
 * server-issued id to resume after ACC off or a reboot: it asks again and the server answers with
 * the offset it holds.
 *
 *     POST  /v1/uploads                {kind, sha256, size, name, meta} -> {offset, complete}
 *     PATCH /v1/uploads/<kind>/<sha>   Upload-Offset: n, bytes [n, n+chunk)
 *           204 + Upload-Offset  next chunk
 *           409 + Upload-Offset  the server is elsewhere: continue from there
 *           200 {complete, sha256}  done, if the sha256 is ours
 *           422 / 404            the server dropped the partial: start again from zero, once
 *
 * Before every chunk the caller's [Gate] may stop it (reverse, a call, the network went, the
 * tether budget is spent). The client never deletes anything; the queue does, on [Outcome.Done].
 */
class UplinkClient(private val http: Http, private val chunkBytes: Int = CHUNK_BYTES) {

    enum class Kind(val wire: String) { ROAD_NOISE("road-noise"), DIAG("diag") }

    /** One HTTP exchange. Header names in [Reply.headers] are lower case. Throws on a dead link. */
    fun interface Http {
        @Throws(IOException::class)
        fun send(method: String, path: String, headers: Map<String, String>, body: ByteArray?): Reply
    }

    data class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    /** Asked before each chunk of [nextBytes]: null to go on, or the reason to stop now. */
    fun interface Gate {
        fun pause(nextBytes: Long): String?
    }

    /** A file waiting in the queue. */
    data class Item(val file: File, val kind: Kind, val name: String, val sha256: String, val meta: JSONObject?)

    sealed interface Outcome {
        /** The server holds the file and confirmed this sha256. */
        data class Done(val sha256: String) : Outcome

        /** The gate said stop. Resume later. */
        data class Paused(val reason: String) : Outcome

        /** The server will never take this file (bad format, too big). */
        data class Rejected(val status: Int, val reason: String) : Outcome

        /** The link or the server failed for now. Resume later. */
        data class Failed(val reason: String) : Outcome
    }

    fun upload(item: Item, gate: Gate, onSent: (Long) -> Unit): Outcome = try {
        send(item, gate, onSent)
    } catch (e: IOException) {
        Outcome.Failed("link: ${e.message}")
    }

    private fun send(item: Item, gate: Gate, onSent: (Long) -> Unit): Outcome {
        val size = item.file.length()
        var restarted = false
        var offset = when (val start = create(item, size, gate, onSent)) {
            is Start.At -> start.offset
            is Start.End -> return start.outcome
        }

        RandomAccessFile(item.file, "r").use { raf ->
            while (offset < size) {
                val n = minOf(chunkBytes.toLong(), size - offset).toInt()
                gate.pause(n + HEADER_BYTES)?.let { return Outcome.Paused(it) }

                val chunk = ByteArray(n)
                raf.seek(offset)
                raf.readFully(chunk)
                val reply = http.send("PATCH", "/v1/uploads/${item.kind.wire}/${item.sha256}",
                    mapOf("Upload-Offset" to offset.toString(), "Content-Type" to OCTETS), chunk)
                onSent(n + HEADER_BYTES)

                when (reply.status) {
                    HTTP_NO_CONTENT, HTTP_CONFLICT -> offset = serverOffset(reply) ?: return failed(reply)
                    HTTP_OK -> return verified(item, reply)
                    HTTP_NOT_FOUND, HTTP_UNPROCESSABLE -> {
                        if (restarted) {
                            return Outcome.Rejected(reply.status, reason(reply))
                        }
                        restarted = true
                        offset = when (val again = create(item, size, gate, onSent)) {
                            is Start.At -> again.offset
                            is Start.End -> return again.outcome
                        }
                    }
                    else -> return failed(reply)
                }
            }
        }
        // Every byte is there but no answer said complete: ask again; the server answers from its record.
        return when (val last = create(item, size, gate, onSent)) {
            is Start.End -> last.outcome
            is Start.At -> Outcome.Failed("server holds all bytes but did not complete")
        }
    }

    /** What a POST says: continue at an offset, or the upload is over one way or another. */
    private sealed interface Start {
        data class At(val offset: Long) : Start
        data class End(val outcome: Outcome) : Start
    }

    /** POST the upload: where to continue, or a final [Outcome]. */
    private fun create(item: Item, size: Long, gate: Gate, onSent: (Long) -> Unit): Start {
        val body = JSONObject()
            .put("kind", item.kind.wire)
            .put("sha256", item.sha256)
            .put("size", size)
            .put("name", item.name)
            .put("meta", item.meta ?: JSONObject())
            .toString().toByteArray()
        gate.pause(body.size + HEADER_BYTES)?.let { return Start.End(Outcome.Paused(it)) }

        val reply = http.send("POST", "/v1/uploads", mapOf("Content-Type" to JSON), body)
        onSent(body.size + HEADER_BYTES)
        return when (reply.status) {
            HTTP_OK, HTTP_CREATED -> {
                val json = JSONObject(reply.body)
                if (json.optBoolean("complete")) Start.End(verified(item, reply)) else Start.At(json.getLong("offset"))
            }
            HTTP_BAD, HTTP_TOO_BIG -> Start.End(Outcome.Rejected(reply.status, reason(reply)))
            else -> Start.End(failed(reply))
        }
    }

    /** Done only if the server's sha256 is ours. Anything else keeps the file on the car. */
    private fun verified(item: Item, reply: Reply): Outcome {
        val json = JSONObject(reply.body)
        val theirs = json.optString("sha256")
        if (!json.optBoolean("complete") || theirs != item.sha256) {
            return Outcome.Failed("server answered complete with sha256 '$theirs'")
        }
        return Outcome.Done(theirs)
    }

    private fun serverOffset(reply: Reply): Long? = reply.headers["upload-offset"]?.toLongOrNull()

    private fun failed(reply: Reply) = Outcome.Failed("HTTP ${reply.status}: ${reason(reply)}")

    private fun reason(reply: Reply): String =
        runCatching { JSONObject(reply.body).optString("error") }.getOrNull().orEmpty()

    companion object {
        /** 256 KiB: a few seconds on a slow tether, so a pause or a drop loses little. */
        const val CHUNK_BYTES = 256 * 1024

        /** Request line, headers and the reply, charged to the budget per exchange. */
        const val HEADER_BYTES = 400L

        private const val JSON = "application/json"
        private const val OCTETS = "application/offset+octet-stream"
        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_BAD = 400
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val HTTP_TOO_BIG = 413
        private const val HTTP_UNPROCESSABLE = 422
    }
}
