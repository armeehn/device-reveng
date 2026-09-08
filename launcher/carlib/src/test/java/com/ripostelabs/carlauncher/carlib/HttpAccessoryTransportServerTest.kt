package com.ripostelabs.carlauncher.carlib

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The transport against a real socket, on the JVM, with no board.
 *
 * `HttpAccessoryTransportTest` pins the parser; this pins the exchange. The case that matters
 * most is the lying board — one that answers 200 and does nothing — because the transport is
 * where that lie would first be believed, and everything above it trusts what comes out.
 *
 * The fake is a `ServerSocket` and forty lines of HTTP/1.1 rather than a server library:
 * `com.sun.net.httpserver` is not on the Android unit-test classpath, and a dependency for one
 * test is a poor trade. One request per connection, `Connection: close`, which is all the
 * transport ever does.
 */
class HttpAccessoryTransportServerTest {

    private lateinit var socket: ServerSocket
    private lateinit var transport: HttpAccessoryTransport

    /** The fake board's state per accessory. Honest by default; tests make it lie. */
    private val state = mutableMapOf("bar" to JSONObject().put("power", "off"))
    @Volatile private var lie = false
    @Volatile private var refuseWith: Int? = null
    private val received = mutableListOf<String>()

    private val bar = Accessory("bar", "Light bar", AccessoryKind.SWITCH)
    private val spot = Accessory("spot", "Spot", AccessoryKind.LEVEL)

    @Before
    fun start() {
        socket = ServerSocket(0)
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { serve(it) }
            }
        }
        transport = HttpAccessoryTransport("http://127.0.0.1:${socket.localPort}")
    }

    @After
    fun stop() {
        socket.close()
    }

    /** Read one request, answer it as the board would, close. */
    private fun serve(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        var contentLength = 0
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toInt()
            }
        }
        val body = CharArray(contentLength).let { buf ->
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        }

        val (method, path) = requestLine.split(' ').let { it[0] to it[1] }
        synchronized(received) { received += "$method $path $body" }

        val id = path.substringAfterLast('/')
        val reply: Pair<Int, String?> = when {
            refuseWith != null -> refuseWith!! to null
            state[id] == null -> 404 to null
            else -> {
                val current = state.getValue(id)
                if (method == "POST" && !lie) {
                    val req = JSONObject(body)
                    if (req.has("power")) current.put("power", req.getString("power"))
                    if (req.has("level")) current.put("level", req.getInt("level"))
                }
                200 to current.toString()
            }
        }

        val (code, payload) = reply
        val bytes = payload?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = "HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun lastRequest(): String = synchronized(received) { received.last() }

    @Test
    fun `an honest board confirms and the state follows`() {
        assertEquals(CommandResult.APPLIED, transport.apply(bar, AccessoryCommand.SetPower(Power.ON)))
        assertEquals(Power.ON, transport.read(bar)!!.power)
    }

    @Test
    fun `the request body is the contract`() {
        transport.apply(bar, AccessoryCommand.SetPower(Power.ON))

        assertEquals("""POST /set/bar {"power":"on"}""", lastRequest())
    }

    @Test
    fun `a level is applied and read back`() {
        state["spot"] = JSONObject().put("power", "on").put("level", 10)

        assertEquals(CommandResult.APPLIED, transport.apply(spot, AccessoryCommand.SetLevel(42)))
        assertEquals(42, transport.read(spot)!!.level)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a board that says 200 and does nothing is REJECTED, not APPLIED`() {
        // The lying board. The status code says yes; the body says the light is still off.
        lie = true

        assertEquals(CommandResult.REJECTED, transport.apply(bar, AccessoryCommand.SetPower(Power.ON)))
    }

    @Test
    fun `a 4xx is REJECTED`() {
        refuseWith = 422

        assertEquals(CommandResult.REJECTED, transport.apply(bar, AccessoryCommand.SetPower(Power.ON)))
    }

    @Test
    fun `a 5xx is REJECTED, not UNREACHABLE`() {
        // The board answered; it is not gone. The controller keeps the last known state.
        refuseWith = 500

        assertEquals(CommandResult.REJECTED, transport.apply(bar, AccessoryCommand.SetPower(Power.ON)))
    }

    @Test
    fun `an accessory the board does not know is REJECTED`() {
        assertEquals(CommandResult.REJECTED, transport.apply(spot, AccessoryCommand.SetPower(Power.ON)))
        assertNull(transport.read(spot))
    }

    @Test
    fun `nothing listening is UNREACHABLE, and a read is null rather than off`() {
        val dead = HttpAccessoryTransport("http://127.0.0.1:1")

        assertEquals(CommandResult.UNREACHABLE, dead.apply(bar, AccessoryCommand.SetPower(Power.ON)))
        assertNull(dead.read(bar))
    }
}
