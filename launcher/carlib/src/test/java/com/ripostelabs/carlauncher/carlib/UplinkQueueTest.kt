package com.ripostelabs.carlauncher.carlib

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The queue's one promise: a capture leaves the car's flash only after the server has said
 * "complete" with the same sha256 the car computed. Every failure mode below must leave the
 * file in place, and every resume must continue where the server stopped, not from zero.
 */
class UplinkQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The ingest protocol (os/uplink/ingest.py) in memory, with switches for its failures. */
    private class FakeServer : UplinkClient.Http {
        val partial = mutableMapOf<String, ByteArray>()
        val sizes = mutableMapOf<String, Int>()
        val done = mutableMapOf<String, ByteArray>()
        var patches = 0
        var bytesReceived = 0L
        var dropAfterPatches = Int.MAX_VALUE
        var lieAboutSha = false
        var refuseCreate = 0
        var corruptOnce = false

        override fun send(method: String, path: String, headers: Map<String, String>, body: ByteArray?): UplinkClient.Reply {
            if (method == "POST") {
                if (refuseCreate != 0) {
                    return reply(refuseCreate, """{"error":"no"}""")
                }
                val req = JSONObject(String(body!!))
                val key = req.getString("kind") + "/" + req.getString("sha256")
                done[key]?.let { return complete(key, it) }
                if (key !in partial) {
                    partial[key] = ByteArray(0)
                    sizes[key] = req.getInt("size")
                }
                return reply(201, """{"offset":${partial[key]!!.size},"complete":false}""")
            }
            val key = path.removePrefix("/v1/uploads/")
            if (method == "PATCH") {
                if (patches >= dropAfterPatches) {
                    throw IOException("link dropped")
                }
                patches++
                val have = partial[key] ?: return reply(404, """{"error":"unknown upload"}""")
                val at = headers.getValue("Upload-Offset").toInt()
                if (at != have.size) {
                    return reply(409, """{"error":"offset"}""", mapOf("upload-offset" to "${have.size}"))
                }
                bytesReceived += body!!.size
                val next = have + body
                if (next.size < sizes.getValue(key)) {
                    partial[key] = next
                    return reply(204, "", mapOf("upload-offset" to "${next.size}"))
                }
                partial.remove(key)
                if (corruptOnce) {
                    corruptOnce = false
                    return reply(422, """{"error":"sha256 mismatch"}""")
                }
                done[key] = next
                return complete(key, next)
            }
            return reply(405, "")
        }

        private fun complete(key: String, bytes: ByteArray): UplinkClient.Reply {
            val sha = if (lieAboutSha) "0".repeat(64) else sha(bytes)
            return reply(200, """{"complete":true,"sha256":"$sha","path":"$key"}""")
        }

        private fun reply(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
            UplinkClient.Reply(status, headers, body)
    }

    private val server = FakeServer()
    private val go = UplinkClient.Gate { null }

    private fun queue() = UplinkQueue(tmp.root.resolve("queue"))

    private fun client(chunk: Int = 1000) = UplinkClient(server, chunkBytes = chunk)

    private fun capture(name: String, size: Int, seed: Int = 1): File {
        val f = tmp.root.resolve(name)
        f.writeBytes(ByteArray(size) { ((it * 31 + seed * 7) and 0xFF).toByte() })
        return f
    }

    private fun sidecar() = JSONObject().put("schema", "road-noise/1").put("band", "city")

    @Test
    fun `a capture is sent in chunks and deleted only after the verified answer`() {
        val q = queue()
        val item = q.add(UplinkClient.Kind.ROAD_NOISE, "w.wav", capture("w.wav", 4500), sidecar())!!
        assertTrue(item.file.exists())

        val result = q.drain(client(), go) {}

        assertEquals(1, result.sent)
        assertEquals(5, server.patches)
        assertFalse(item.file.exists())
        assertEquals(0, q.queuedBytes())
        assertEquals(sha(capture("ref.wav", 4500)), result.lastSha)
    }

    @Test
    fun `a complete answer with another sha256 keeps the file`() {
        server.lieAboutSha = true
        val q = queue()
        val item = q.add(UplinkClient.Kind.ROAD_NOISE, "w.wav", capture("w.wav", 2500), sidecar())!!

        val result = q.drain(client(), go) {}

        assertTrue(result.stop is UplinkClient.Outcome.Failed)
        assertTrue(item.file.exists())
    }

    @Test
    fun `a dropped link keeps the file and the next boot resumes at the server's offset`() {
        val q = queue()
        val item = q.add(UplinkClient.Kind.ROAD_NOISE, "w.wav", capture("w.wav", 5000), sidecar())!!
        server.dropAfterPatches = 2

        val first = q.drain(client(), go) {}
        assertTrue(first.stop is UplinkClient.Outcome.Failed)
        assertTrue(item.file.exists())
        assertEquals(2000L, server.bytesReceived)

        // A reboot: a fresh queue and client over the same directory.
        server.dropAfterPatches = Int.MAX_VALUE
        val second = UplinkQueue(tmp.root.resolve("queue")).drain(client(), go) {}

        assertEquals(1, second.sent)
        assertEquals(5000L, server.bytesReceived)
        assertFalse(item.file.exists())
    }

    @Test
    fun `reverse or a call pauses between chunks and nothing more is sent`() {
        val q = queue()
        val item = q.add(UplinkClient.Kind.ROAD_NOISE, "w.wav", capture("w.wav", 5000), sidecar())!!
        var chunks = 0
        val gate = UplinkClient.Gate { if (chunks >= 2) "reverse" else null }

        // Count data chunks only; the POST that opens the upload also reports its bytes.
        val result = q.drain(client(), gate) { n -> if (n >= 1000) chunks++ }

        assertEquals(UplinkClient.Outcome.Paused("reverse"), result.stop)
        assertEquals(2, server.patches)
        assertTrue(item.file.exists())
    }

    @Test
    fun `the tether budget stops the upload and is charged for what was sent`() {
        val store = object : DataBudget.Store {
            override var month = ""
            override var usedBytes = 0L
        }
        val budget = DataBudget(store, limitBytes = { 3000 })
        val gate = UplinkClient.Gate { n -> if (budget.allows(DataBudget.Link.METERED, n)) null else "budget" }
        val q = queue()
        q.add(UplinkClient.Kind.ROAD_NOISE, "w.wav", capture("w.wav", 5000), sidecar())

        val result = q.drain(client(), gate) { n -> budget.charge(DataBudget.Link.METERED, n) }

        assertEquals(UplinkClient.Outcome.Paused("budget"), result.stop)
        assertTrue(budget.usedBytes() <= 3000)
        assertTrue(budget.usedBytes() > 0)
    }

    @Test
    fun `a server that lost the partial gets the file again from zero`() {
        server.corruptOnce = true
        val q = queue()
        val item = q.add(UplinkClient.Kind.ROAD_NOISE, "w.wav", capture("w.wav", 2500), sidecar())!!

        val result = q.drain(client(), go) {}

        assertEquals(1, result.sent)
        assertFalse(item.file.exists())
    }

    @Test
    fun `a file the server refuses for good is set aside and the queue moves on`() {
        val q = queue()
        val bad = q.add(UplinkClient.Kind.ROAD_NOISE, "bad.wav", capture("bad.wav", 100, seed = 2), sidecar())!!
        server.refuseCreate = 400
        val result = q.drain(client(), go) {}

        assertTrue(result.stop is UplinkClient.Outcome.Rejected || result.rejected == 1)
        assertFalse(bad.file.exists())
        assertTrue(tmp.root.resolve("queue/rejected").listFiles()!!.isNotEmpty())
    }

    @Test
    fun `quota and full disk on the server are retried later, not rejected`() {
        val q = queue()
        val item = q.add(UplinkClient.Kind.ROAD_NOISE, "w.wav", capture("w.wav", 100), sidecar())!!
        server.refuseCreate = 429
        val result = q.drain(client(), go) {}

        assertTrue(result.stop is UplinkClient.Outcome.Failed)
        assertTrue(item.file.exists())
    }

    @Test
    fun `road noise goes before logs, oldest first`() {
        val q = queue()
        val log = q.add(UplinkClient.Kind.DIAG, "logcat.txt.3.gz", capture("l", 10, seed = 3), null)!!
        val older = q.add(UplinkClient.Kind.ROAD_NOISE, "a.wav", capture("a", 10, seed = 4), sidecar())!!
        older.file.setLastModified(1_000_000)
        val newer = q.add(UplinkClient.Kind.ROAD_NOISE, "b.wav", capture("b", 10, seed = 5), sidecar())!!
        newer.file.setLastModified(2_000_000)

        assertEquals(listOf(older.sha256, newer.sha256, log.sha256), q.items().map { it.sha256 })
    }

    @Test
    fun `a log already sent is not queued again`() {
        val q = queue()
        q.add(UplinkClient.Kind.DIAG, "logcat.txt.3.gz", capture("l1", 300, seed = 6), null)
        q.drain(client(), go) {}

        assertNull(q.add(UplinkClient.Kind.DIAG, "logcat.txt.4.gz", capture("l2", 300, seed = 6), null))
        assertTrue(q.items().isEmpty())
    }

    @Test
    fun `a capture already on the server completes without sending a byte`() {
        val q = queue()
        q.add(UplinkClient.Kind.ROAD_NOISE, "w.wav", capture("w.wav", 2000), sidecar())
        q.drain(client(), go) {}
        val again = q.add(UplinkClient.Kind.ROAD_NOISE, "w2.wav", capture("w2.wav", 2000), sidecar())!!
        val before = server.patches

        q.drain(client(), go) {}

        assertEquals(before, server.patches)
        assertFalse(again.file.exists())
    }

    @Test
    fun `the sidecar travels with the capture across a restart`() {
        val q = queue()
        val item = q.add(UplinkClient.Kind.ROAD_NOISE, "w.wav", capture("w.wav", 10), sidecar())!!
        val reread = UplinkQueue(tmp.root.resolve("queue")).items().single()
        assertEquals(item.sha256, reread.sha256)
        assertEquals("city", reread.meta!!.getString("band"))
        assertEquals("w.wav", reread.name)
    }

    private companion object {
        fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha(file: File): String = sha(file.readBytes())
    }
}
