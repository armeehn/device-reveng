package com.ripostelabs.carlauncher.carlib

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HttpAccessoryTransport — an accessory board on the car's own network.
 *
 * The first concrete transport, chosen for lights and servos: a microcontroller doing PWM wants
 * to sit on the MikroTik's WiFi, reachable by name, and never touch the vehicle bus. The contract
 * it speaks is small enough to hand-write on any board and is documented in
 * `can-integration/docs/ACCESSORY_BOARD.md`:
 *
 *     GET  {base}/state/{id}            → 200 {"power":"on"|"off","level":0..100}
 *     POST {base}/set/{id}  {"power":"on"}  or  {"level":42}
 *                                       → 200 with the state as it now IS   (APPLIED)
 *                                       → 4xx                               (REJECTED)
 *                                       → no answer / timeout               (UNREACHABLE)
 *
 * ── The reply is the truth, not the request ─────────────────────────────────────────────────────
 * A 200 is only APPLIED if the body confirms the state we asked for. A board that answers 200 to
 * everything and does nothing is exactly the device [AccessoryController] must not trust, and a
 * transport that treated the status code as confirmation would hand it that trust. The body is
 * read back and compared.
 *
 * ── Timeouts are short ──────────────────────────────────────────────────────────────────────────
 * This runs on a worker thread, but a sequence step waits on it, and a driver waiting on a
 * light that is not going to answer should find out in a second rather than thirty.
 */
class HttpAccessoryTransport(
    baseUrl: String,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : AccessoryTransport {

    private val base = baseUrl.trimEnd('/')

    override fun apply(accessory: Accessory, command: AccessoryCommand): CommandResult {
        val body = JSONObject()
        when (command) {
            is AccessoryCommand.SetPower -> body.put(KEY_POWER, command.power.wire())
            is AccessoryCommand.SetLevel -> body.put(KEY_LEVEL, command.level)
        }

        val reply = exchange("POST", "$base/set/${accessory.id}", body.toString())
            ?: return CommandResult.UNREACHABLE

        if (reply.status !in HTTP_OK_RANGE) {
            return CommandResult.REJECTED
        }

        // 200 alone proves the board heard us. Only the body proves it did what we asked.
        val state = parseState(reply.body) ?: return CommandResult.REJECTED
        val confirmed = when (command) {
            is AccessoryCommand.SetPower -> state.power == command.power
            is AccessoryCommand.SetLevel -> state.level == command.level
        }

        return if (confirmed) CommandResult.APPLIED else CommandResult.REJECTED
    }

    override fun read(accessory: Accessory): AccessoryState? {
        val reply = exchange("GET", "$base/state/${accessory.id}", null) ?: return null
        if (reply.status !in HTTP_OK_RANGE) {
            return null
        }

        return parseState(reply.body)
    }

    private data class Reply(val status: Int, val body: String)

    /** Failure causes already logged, so a board that is down does not fill the log at 5 s. */
    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    /** One request. Null when nothing answered — the transport-level meaning of UNREACHABLE. */
    private fun exchange(method: String, url: String, body: String?): Reply? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", CONTENT_JSON)
                // One request per connection, said out loud. A microcontroller board answers
                // HTTP/1.0 and closes; letting the client pool that socket means the next
                // command first fails on a dead connection and then retries. Say close, and
                // there is nothing to pool.
                setRequestProperty("Connection", "close")
                useCaches = false
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", CONTENT_JSON)
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }

            val status = conn.responseCode
            val stream = if (status in HTTP_OK_RANGE) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            Reply(status, text)
        } catch (e: IOException) {
            // UNREACHABLE without a reason cost an iteration on the emulator: a cleartext block
            // looked identical to an unplugged board. Log each distinct cause once, not per poll.
            val cause = "${e.javaClass.simpleName}: ${e.message}"
            if (reported.add(cause)) {
                Log.w(LOG_TAG, "$method $url -> $cause")
            }
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val LOG_TAG = "Accessories"
        private const val CONNECT_TIMEOUT_MS = 1_000
        private const val READ_TIMEOUT_MS = 1_000
        private val HTTP_OK_RANGE = HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE
        private const val CONTENT_JSON = "application/json"

        private const val KEY_POWER = "power"
        private const val KEY_LEVEL = "level"
        private const val WIRE_ON = "on"
        private const val WIRE_OFF = "off"

        private fun Power.wire(): String = if (this == Power.ON) WIRE_ON else WIRE_OFF

        /**
         * Parse a state body. Null for anything that is not the contract — an HTML error page,
         * an empty reply — so a board speaking something else reads as REJECTED, never as "off".
         * Pure and exposed for tests, which is why the wire format lives here and nowhere else.
         */
        fun parseState(body: String): AccessoryState? {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

            val power = when (json.optString(KEY_POWER, "")) {
                WIRE_ON -> Power.ON
                WIRE_OFF -> Power.OFF
                "" -> null
                else -> return null
            }
            val level = if (json.has(KEY_LEVEL)) json.optInt(KEY_LEVEL, -1).takeIf { it in 0..100 } ?: return null else null

            if (power == null && level == null) {
                return null
            }

            return AccessoryState(power = power, level = level)
        }
    }
}
