package com.ripostelabs.claudecar.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One SSE event from the claude-car backend (server.py on x). */
sealed interface ChatEvent {
    data class Text(val text: String) : ChatEvent
    data class Tool(val name: String) : ChatEvent
    data class Error(val text: String) : ChatEvent
    data object Done : ChatEvent
}

/**
 * Client for the claude-car backend: POST /chat streams SSE `data:` lines,
 * POST /reset drops the server-side session, GET /health pings.
 * Plain HttpURLConnection — no third-party HTTP deps.
 */
object ClaudeApi {

    private fun open(baseUrl: String, path: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
        }

    fun chat(baseUrl: String, clientId: String, message: String): Flow<ChatEvent> = flow {
        val conn = runCatching {
            open(baseUrl, "/chat").apply {
                requestMethod = "POST"
                doOutput = true
                // A turn can legitimately run for minutes while Claude uses tools;
                // 0 = no read timeout, the stream ends when the server closes it.
                readTimeout = 0
                setRequestProperty("Content-Type", "application/json")
            }
        }.getOrElse {
            emit(ChatEvent.Error("bad server URL: ${baseUrl.ifBlank { "(not set)" }}"))
            return@flow
        }
        try {
            val body = JSONObject().put("message", message).put("client", clientId)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                emit(ChatEvent.Error("server replied HTTP ${conn.responseCode}"))
                return@flow
            }
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data: ")) continue
                    val evt = runCatching { JSONObject(line.removePrefix("data: ")) }
                        .getOrNull() ?: continue
                    when (evt.optString("type")) {
                        "text" -> emit(ChatEvent.Text(evt.optString("text")))
                        "tool" -> emit(ChatEvent.Tool(evt.optString("name", "tool")))
                        "error" -> emit(ChatEvent.Error(evt.optString("text", "error")))
                        // The server keeps the socket alive after the turn, so EOF
                        // never comes — `done` IS end-of-turn. A bare `error` with no
                        // `done` (claude exited nonzero) is terminal too.
                        "done" -> {
                            emit(ChatEvent.Done)
                            return@useLines
                        }
                    }
                    if (evt.optString("type") == "error") return@useLines
                }
            }
        } catch (e: Exception) {
            emit(ChatEvent.Error(e.message ?: e.javaClass.simpleName))
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun reset(baseUrl: String, clientId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open(baseUrl, "/reset").apply {
                requestMethod = "POST"
                doOutput = true
                readTimeout = 8_000
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use {
                    it.write(JSONObject().put("client", clientId).toString().toByteArray())
                }
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    suspend fun health(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open(baseUrl, "/health").apply { readTimeout = 8_000 }
            try {
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }
}
