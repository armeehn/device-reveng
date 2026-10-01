package com.ripostelabs.carlauncher.carlib

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * UplinkQueue: the files waiting on the car's flash for the next network, and the only code that
 * deletes them.
 *
 *     <dir>/<sha256>.<ext>        the capture or log, renamed in (never copied twice)
 *     <dir>/<sha256>.item.json    kind, original name, sidecar
 *     <dir>/diag-sent.txt         sha256 of every log already delivered (logs rotate and rename)
 *     <dir>/rejected/             files the server refused for good, kept for a human, capped
 *
 * A file is deleted only on [UplinkClient.Outcome.Done] whose sha256 equals the file's own.
 * Everything survives ACC off and reboots: the next [drain] picks up the same items, and the
 * client resumes each at the offset the server holds.
 */
class UplinkQueue(private val dir: File) {

    /** What one [drain] did: files delivered, files set aside, and why it stopped (null = empty). */
    data class Drain(val sent: Int, val rejected: Int, val stop: UplinkClient.Outcome?, val lastSha: String?)

    init {
        dir.mkdirs()
    }

    /**
     * Move [source] into the queue. Returns null for a log that was already delivered or is
     * already queued under the same content.
     */
    fun add(kind: UplinkClient.Kind, name: String, source: File, meta: JSONObject?): UplinkClient.Item? {
        val sha = sha256(source)
        if (kind == UplinkClient.Kind.DIAG && (sha in sentLogs() || itemFile(sha).exists())) {
            source.delete()
            return null
        }

        val data = File(dir, "$sha.${source.extension.ifEmpty { DEFAULT_EXT }}")
        if (!source.renameTo(data)) {
            source.copyTo(data, overwrite = true)
            source.delete()
        }
        val record = JSONObject().put("kind", kind.name).put("name", name).put("data", data.name)
        meta?.let { record.put("meta", it) }
        itemFile(sha).writeText(record.toString())
        return UplinkClient.Item(data, kind, name, sha, meta)
    }

    /** Road noise first (it is what the trainer waits for), then logs; oldest first in each. */
    fun items(): List<UplinkClient.Item> =
        (dir.listFiles { f -> f.name.endsWith(ITEM_SUFFIX) } ?: emptyArray())
            .mapNotNull { read(it) }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.file.lastModified() }))

    fun queuedBytes(): Long = items().sumOf { it.file.length() }

    fun drain(client: UplinkClient, gate: UplinkClient.Gate, onSent: (Long) -> Unit): Drain {
        var sent = 0
        var rejected = 0
        var lastSha: String? = null
        for (item in items()) {
            when (val outcome = client.upload(item, gate, onSent)) {
                is UplinkClient.Outcome.Done -> {
                    if (outcome.sha256 != item.sha256) {
                        return Drain(sent, rejected, UplinkClient.Outcome.Failed("sha256 changed"), lastSha)
                    }
                    finish(item)
                    sent++
                    lastSha = item.sha256
                }
                is UplinkClient.Outcome.Rejected -> {
                    setAside(item)
                    rejected++
                }
                else -> return Drain(sent, rejected, outcome, lastSha)
            }
        }
        return Drain(sent, rejected, null, lastSha)
    }

    private fun finish(item: UplinkClient.Item) {
        if (item.kind == UplinkClient.Kind.DIAG) {
            remember(item.sha256)
        }
        item.file.delete()
        itemFile(item.sha256).delete()
    }

    private fun setAside(item: UplinkClient.Item) {
        val aside = File(dir, REJECTED).apply { mkdirs() }
        item.file.renameTo(File(aside, item.file.name))
        itemFile(item.sha256).renameTo(File(aside, itemFile(item.sha256).name))

        // Keep the newest few for a human to look at; older ones go.
        val all = aside.listFiles()!!.sortedByDescending { it.lastModified() }
        all.drop(MAX_REJECTED_FILES).forEach { it.delete() }
    }

    private fun read(f: File): UplinkClient.Item? {
        val json = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val data = File(dir, json.getString("data"))
        if (!data.exists()) {
            f.delete()
            return null
        }
        val kind = runCatching { UplinkClient.Kind.valueOf(json.getString("kind")) }.getOrNull() ?: return null
        return UplinkClient.Item(data, kind, json.getString("name"), f.name.removeSuffix(ITEM_SUFFIX),
            json.optJSONObject("meta"))
    }

    private fun itemFile(sha: String) = File(dir, sha + ITEM_SUFFIX)

    private fun sentLogs(): Set<String> {
        val f = File(dir, SENT_LOGS)
        return if (f.exists()) f.readLines().toSet() else emptySet()
    }

    private fun remember(sha: String) {
        val kept = (sentLogs().toList() + sha).takeLast(MAX_SENT_LOGS)
        File(dir, SENT_LOGS).writeText(kept.joinToString("\n", postfix = "\n"))
    }

    companion object {
        private const val ITEM_SUFFIX = ".item.json"
        private const val SENT_LOGS = "diag-sent.txt"
        private const val REJECTED = "rejected"
        private const val DEFAULT_EXT = "bin"
        private const val MAX_SENT_LOGS = 1000
        private const val MAX_REJECTED_FILES = 20

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) {
                        break
                    }
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private const val BUFFER = 64 * 1024
    }
}
