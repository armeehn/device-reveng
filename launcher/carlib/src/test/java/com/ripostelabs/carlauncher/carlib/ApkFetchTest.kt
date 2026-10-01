package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads cross a phone's data plan and a link that drops at every ACC off, so they resume
 * from the bytes already on flash, stop when the gate says so, and keep nothing that fails its
 * sha256.
 */
class ApkFetchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val blob = ByteArray(10_000) { (it * 31 + 7).toByte() }

    /** The ingest server's GET /v1/releases/<path> with Range, plus switches for its failures. */
    private inner class Server : ApkFetch.Http {
        var content = blob
        var gets = 0
        var dropAfter = Int.MAX_VALUE
        var status = 206

        override fun range(path: String, from: Long, to: Long): ApkFetch.Reply {
            if (gets >= dropAfter) {
                throw IOException("link dropped")
            }
            gets++
            val end = minOf(to, content.size - 1L).toInt()
            return ApkFetch.Reply(status, content.copyOfRange(from.toInt(), end + 1))
        }
    }

    private fun app(sha: String = sha(blob)) = ReleaseManifest.App(
        ReleaseManifest.Role.SUITE, "com.ripostelabs.clock", 4, "1.4", "suite/com.ripostelabs.clock.apk", sha, blob.size.toLong(),
    )

    private val open = UplinkClient.Gate { null }

    @Test
    fun `downloads in chunks, verifies, and charges every byte`() {
        val server = Server()
        var charged = 0L
        val r = ApkFetch(server, tmp.root, chunkBytes = 3000).fetch(app(), open) { charged += it }
        assertTrue(r is ApkFetch.Result.Done)
        assertArrayEquals(blob, (r as ApkFetch.Result.Done).file.readBytes())
        assertEquals(4, server.gets)
        assertEquals(blob.size.toLong(), charged)
    }

    @Test
    fun `a dropped link resumes from the bytes on flash`() {
        val server = Server().apply { dropAfter = 2 }
        val fetch = ApkFetch(server, tmp.root, chunkBytes = 3000)
        assertTrue(fetch.fetch(app(), open) {} is ApkFetch.Result.Failed)
        server.dropAfter = Int.MAX_VALUE
        val before = server.gets
        val r = fetch.fetch(app(), open) {}
        assertTrue(r is ApkFetch.Result.Done)
        assertEquals("only the two missing chunks", 2, server.gets - before)
    }

    @Test
    fun `the gate pauses between chunks and keeps the partial`() {
        val server = Server()
        var allowed = 1
        val gate = UplinkClient.Gate { if (allowed-- > 0) null else "tether budget used up" }
        val fetch = ApkFetch(server, tmp.root, chunkBytes = 3000)
        val r = fetch.fetch(app(), gate) {}
        assertEquals(ApkFetch.Result.Paused("tether budget used up"), r)
        assertEquals(1, server.gets)
        assertEquals(3000L, fetch.partBytes(app()))
    }

    @Test
    fun `a sha256 mismatch keeps nothing`() {
        val server = Server()
        val fetch = ApkFetch(server, tmp.root, chunkBytes = 3000)
        val r = fetch.fetch(app(sha = "0".repeat(64)), open) {}
        assertEquals(ApkFetch.Result.Corrupt, r)
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test
    fun `a finished file is not fetched again`() {
        val server = Server()
        val fetch = ApkFetch(server, tmp.root, chunkBytes = 3000)
        fetch.fetch(app(), open) {}
        val gets = server.gets
        assertTrue(fetch.fetch(app(), open) {} is ApkFetch.Result.Done)
        assertEquals(gets, server.gets)
    }

    @Test
    fun `a server error is a failure and keeps the partial`() {
        val server = Server()
        val fetch = ApkFetch(server, tmp.root, chunkBytes = 3000)
        server.dropAfter = 1
        fetch.fetch(app(), open) {}
        server.dropAfter = Int.MAX_VALUE
        server.status = 404
        assertTrue(fetch.fetch(app(), open) {} is ApkFetch.Result.Failed)
        assertEquals(3000L, fetch.partBytes(app()))
    }

    @Test
    fun `remaining bytes count what is already on flash`() {
        val server = Server().apply { dropAfter = 1 }
        val fetch = ApkFetch(server, tmp.root, chunkBytes = 3000)
        assertEquals(blob.size.toLong(), fetch.remaining(app()))
        fetch.fetch(app(), open) {}
        assertEquals(blob.size - 3000L, fetch.remaining(app()))
    }

    @Test
    fun `other versions of the package are cleared`() {
        val fetch = ApkFetch(Server(), tmp.root, chunkBytes = 3000)
        tmp.newFile("com.ripostelabs.clock-3.apk")
        tmp.newFile("com.ripostelabs.clock-2.part")
        tmp.newFile("com.ripostelabs.notes-2.apk")
        fetch.fetch(app(), open) {}
        val names = tmp.root.list()!!.sorted()
        assertEquals(listOf("com.ripostelabs.clock-4.apk", "com.ripostelabs.notes-2.apk"), names)
        assertFalse(names.any { it.endsWith(".part") })
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
