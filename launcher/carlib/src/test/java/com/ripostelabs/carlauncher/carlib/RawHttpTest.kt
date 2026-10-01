package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Proxy
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The hand-rolled HTTP must carry PATCH with a body and read headers and bodies back exactly. */
class RawHttpTest {

    /** One connection: record the request, answer [reply]. */
    private fun serveOnce(reply: String, seen: StringBuilder): Int {
        val server = ServerSocket(0)
        thread {
            server.accept().use { s ->
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                var length = 0
                while (true) {
                    val l = r.readLine() ?: break
                    seen.append(l).append('\n')
                    if (l.startsWith("Content-Length:")) {
                        length = l.substringAfter(':').trim().toInt()
                    }
                    if (l.isEmpty()) {
                        break
                    }
                }
                val body = CharArray(length)
                var at = 0
                while (at < length) {
                    at += r.read(body, at, length - at)
                }
                seen.append(String(body))
                s.getOutputStream().write(reply.toByteArray(Charsets.ISO_8859_1))
            }
            server.close()
        }
        return server.localPort
    }

    @Test
    fun `PATCH carries its body and offset, and the reply headers come back lower case`() {
        val seen = StringBuilder()
        val port = serveOnce("HTTP/1.1 204 No Content\r\nUpload-Offset: 5\r\nContent-Length: 0\r\n\r\n", seen)
        val reply = RawHttp("127.0.0.1", port, Proxy.NO_PROXY)
            .send("PATCH", "/v1/uploads/diag/abc", mapOf("Upload-Offset" to "0"), "hello".toByteArray())

        assertEquals(204, reply.status)
        assertEquals("5", reply.headers["upload-offset"])
        val req = seen.toString()
        assertEquals("PATCH /v1/uploads/diag/abc HTTP/1.1", req.lines().first())
        assert(req.contains("Upload-Offset: 0")) { req }
        assert(req.contains("Content-Length: 5")) { req }
        assert(req.endsWith("hello")) { req }
    }

    @Test
    fun `a JSON body is read to its length`() {
        val json = """{"complete": true, "sha256": "ab"}"""
        val port = serveOnce("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${json.length}\r\n\r\n$json", StringBuilder())
        val reply = RawHttp("127.0.0.1", port, Proxy.NO_PROXY).send("GET", "/v1/wants", emptyMap(), null)
        assertEquals(200, reply.status)
        assertEquals(json, reply.body)
    }

    @Test
    fun `a release range comes back as exact bytes`() {
        // Every byte value, including ones that are not valid UTF-8: an APK must not be decoded.
        val bytes = String(ByteArray(256) { it.toByte() }, Charsets.ISO_8859_1)
        val seen = StringBuilder()
        val port = serveOnce("HTTP/1.1 206 Partial Content\r\nContent-Length: 256\r\n\r\n$bytes", seen)
        val reply = RawHttp("127.0.0.1", port, Proxy.NO_PROXY).range("suite/com.ripostelabs.clock.apk", 10, 265)
        assertEquals(206, reply.status)
        assertEquals((0 until 256).map { it.toByte() }, reply.body.toList())
        assertEquals("GET /v1/releases/suite/com.ripostelabs.clock.apk HTTP/1.1", seen.lines().first())
        assert(seen.contains("Range: bytes=10-265")) { seen }
    }

    @Test
    fun `the endpoint file must be a plain http URL with a port`() {
        assertEquals(null, RawHttp.of("not a url"))
        assertNull(RawHttp.of("https://example.org"))
        assertEquals(true, RawHttp.of("http://192.0.2.1:8797\n") != null)
    }
}
