package com.reveng.claudecar

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reveng.claudecar.net.ChatEvent
import com.reveng.claudecar.net.ClaudeApi
import kotlinx.coroutines.launch
import java.util.UUID

enum class Role { USER, ASSISTANT, ERROR }

data class Message(
    val role: Role,
    val text: String,
    /** Names of tools Claude used while producing this message. */
    val tools: List<String> = emptyList(),
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        // Baked in from local.properties at build time (tailnet address of x);
        // empty in a plain checkout — then the user sets the URL in-app.
        val DEFAULT_SERVER: String = BuildConfig.DEFAULT_SERVER
    }

    private val prefs = app.getSharedPreferences("claudecar", Context.MODE_PRIVATE)

    /** Stable per-install id: the backend keys its persistent Claude session on it. */
    val clientId: String = prefs.getString("client_id", null) ?: UUID.randomUUID().toString()
        .also { prefs.edit().putString("client_id", it).apply() }

    var serverUrl by mutableStateOf(prefs.getString("server_url", DEFAULT_SERVER)!!)
        private set

    val messages = mutableStateListOf<Message>()
    var streaming by mutableStateOf(false)
        private set

    /** null = unknown/checking, true/false = last /health result. */
    var online by mutableStateOf<Boolean?>(null)
        private set

    init {
        checkHealth()
    }

    fun checkHealth() {
        online = null
        viewModelScope.launch { online = ClaudeApi.health(serverUrl) }
    }

    fun setServer(url: String) {
        val clean = url.trim().ifEmpty { DEFAULT_SERVER }
        serverUrl = if (clean.startsWith("http")) clean else "http://$clean"
        prefs.edit().putString("server_url", serverUrl).apply()
        checkHealth()
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || streaming) return
        streaming = true
        messages += Message(Role.USER, text)
        messages += Message(Role.ASSISTANT, "")
        val idx = messages.lastIndex
        viewModelScope.launch {
            ClaudeApi.chat(serverUrl, clientId, text).collect { evt ->
                when (evt) {
                    is ChatEvent.Text -> {
                        val cur = messages[idx]
                        val joined =
                            if (cur.text.isEmpty()) evt.text else cur.text + "\n\n" + evt.text
                        messages[idx] = cur.copy(text = joined)
                    }
                    is ChatEvent.Tool -> {
                        val cur = messages[idx]
                        messages[idx] = cur.copy(tools = cur.tools + evt.name)
                    }
                    is ChatEvent.Error -> messages += Message(Role.ERROR, evt.text)
                    ChatEvent.Done -> online = true
                }
            }
            // Drop the placeholder if the turn produced neither text nor tool use.
            if (messages[idx].text.isEmpty() && messages[idx].tools.isEmpty()) {
                messages.removeAt(idx)
            }
            streaming = false
        }
    }

    fun newChat() {
        if (streaming) return
        viewModelScope.launch {
            ClaudeApi.reset(serverUrl, clientId)
            messages.clear()
        }
    }
}
