package com.ripostelabs.carlauncher.carlib

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI

/**
 * RawHttp: just enough HTTP/1.1 for the uplink, one request per connection, through a proxy.
 *
 * Android's HttpURLConnection refuses the PATCH verb the resumable protocol uses, and the only
 * way onto the tailnet is the local tailscaled's SOCKS5 port, so the client speaks the protocol
 * itself over a [Socket]: request line, headers, Content-Length body; the reply is read to its
 * Content-Length (the ingest server always sends one) or to the close.
 */
class RawHttp(
    private val host: String,
    private val port: Int,
    private val proxy: Proxy = Proxy.NO_PROXY,
    private val timeoutMs: Int = TIMEOUT_MS,
) : UplinkClient.Http {

    override fun send(method: String, path: String, headers: Map<String, String>, body: ByteArray?): UplinkClient.Reply {
        Socket(proxy).use { socket ->
            socket.soTimeout = timeoutMs
            // The endpoint is an IP literal, so no lookup happens here or in the proxy.
            socket.connect(InetSocketAddress(host, port), timeoutMs)

            val head = StringBuilder()
                .append("$method $path HTTP/1.1\r\n")
                .append("Host: $host:$port\r\n")
                .append("Connection: close\r\n")
                .append("Content-Length: ${body?.size ?: 0}\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("\r\n")

            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            body?.let { out.write(it) }
            out.flush()
            return read(BufferedInputStream(socket.getInputStream()), method)
        }
    }

    private fun read(input: InputStream, method: String): UplinkClient.Reply {
        val status = line(input) ?: throw IOException("no reply")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status: $status")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = line(input) ?: break
            if (h.isEmpty()) {
                break
            }
            val colon = h.indexOf(':')
            if (colon > 0) {
                headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
            }
        }
        if (method == "HEAD" || code == NO_CONTENT) {
            return UplinkClient.Reply(code, headers, "")
        }
        val length = headers["content-length"]?.toIntOrNull()
        val body = if (length != null) exactly(input, length) else input.readBytes()
        return UplinkClient.Reply(code, headers, String(body, Charsets.UTF_8))
    }

    private fun line(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            }
            if (b == '\n'.code) {
                return buf.toString("ISO-8859-1").trimEnd('\r')
            }
            buf.write(b)
        }
    }

    private fun exactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            val r = input.read(out, at, n - at)
            if (r < 0) {
                throw IOException("reply cut at $at of $n bytes")
            }
            at += r
        }
        return out
    }

    companion object {
        const val TIMEOUT_MS = 30_000
        private const val NO_CONTENT = 204

        /** The local tailscaled's SOCKS5 port (os/overlay riposte-uplink.sh). */
        val TAILNET: Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1055))

        /** `http://host:port` from the unit's endpoint file; null if it is not one. */
        fun of(endpoint: String, proxy: Proxy = TAILNET): RawHttp? {
            val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "http" || uri.host == null || uri.port <= 0) {
                return null
            }
            return RawHttp(uri.host, uri.port, proxy)
        }
    }
}
