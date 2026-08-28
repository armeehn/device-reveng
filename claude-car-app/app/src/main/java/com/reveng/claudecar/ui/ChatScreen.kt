package com.reveng.claudecar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveng.claudecar.ChatViewModel
import com.reveng.claudecar.Message
import com.reveng.claudecar.Role

private val QuickPrompts = listOf(
    "What's the weather ahead?",
    "Summarize my day",
    "Find a coffee stop on my route",
    "Explain something interesting",
)

@Composable
fun ChatScreen(vm: ChatViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopBar(vm)
        Transcript(vm, Modifier.weight(1f))
        InputBar(vm)
    }
}

@Composable
private fun TopBar(vm: ChatViewModel) {
    var showSettings by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✳", color = MaterialTheme.colorScheme.primary, fontSize = 26.sp)
        Spacer(Modifier.width(12.dp))
        Text("Claude", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        StatusDot(vm.online)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { showSettings = true }) { Text("Server") }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = vm::newChat, enabled = !vm.streaming) { Text("New chat") }
    }

    if (showSettings) {
        ServerDialog(
            current = vm.serverUrl,
            onDismiss = { showSettings = false },
            onSave = { vm.setServer(it); showSettings = false },
        )
    }
}

@Composable
private fun StatusDot(online: Boolean?) {
    val (color, label) = when (online) {
        true -> ColorOk to "online"
        false -> ColorError to "offline"
        null -> ColorDim to "checking…"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(12.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = ColorDim, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ServerDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var url by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backend server") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                label = { Text("URL") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onSave(url) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Transcript(vm: ChatViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    val last = vm.messages.lastOrNull()

    // Follow the stream: any growth of the last message keeps it in view.
    LaunchedEffect(vm.messages.size, last?.text?.length, last?.tools?.size) {
        if (vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.lastIndex)
        }
    }

    if (vm.messages.isEmpty()) {
        EmptyState(vm, modifier)
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 24.dp, vertical = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(vm.messages) { i, msg ->
            val isLastStreaming = vm.streaming && i == vm.messages.lastIndex
            MessageBubble(msg, isLastStreaming)
        }
    }
}

@Composable
private fun EmptyState(vm: ChatViewModel, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("✳", color = MaterialTheme.colorScheme.primary, fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Ask Claude anything",
            style = MaterialTheme.typography.titleLarge,
            color = ColorDim,
        )
        Spacer(Modifier.height(20.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(QuickPrompts) { _, p ->
                OutlinedButton(onClick = { vm.send(p) }, enabled = !vm.streaming) { Text(p) }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: Message, streaming: Boolean) {
    val fromUser = msg.role == Role.USER
    Row(Modifier.fillMaxWidth()) {
        if (fromUser) Spacer(Modifier.weight(1f, fill = true))
        Surface(
            color = when (msg.role) {
                Role.USER -> ColorUserBubble
                Role.ASSISTANT -> MaterialTheme.colorScheme.surface
                Role.ERROR -> MaterialTheme.colorScheme.surface
            },
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (fromUser) 18.dp else 4.dp,
                bottomEnd = if (fromUser) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 1100.dp),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                if (msg.tools.isNotEmpty()) {
                    ToolChips(msg.tools)
                    Spacer(Modifier.height(8.dp))
                }
                when {
                    msg.role == Role.ERROR -> Text(
                        msg.text,
                        color = ColorError,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    msg.text.isEmpty() && streaming -> Thinking()
                    else -> Text(msg.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (!fromUser) Spacer(Modifier.weight(1f, fill = true))
    }
}

@Composable
private fun ToolChips(tools: List<String>) {
    // Collapse repeats ("Read ×3") so long tool runs stay one line.
    val counted = tools.groupingBy { it }.eachCount()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(counted.entries.toList()) { _, (name, n) ->
            Surface(color = ColorToolChip, shape = RoundedCornerShape(8.dp)) {
                Text(
                    if (n > 1) "⚙ $name ×$n" else "⚙ $name",
                    color = ColorDim,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Thinking() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            Modifier.size(20.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text("Thinking…", color = ColorDim, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InputBar(vm: ChatViewModel) {
    var draft by remember { mutableStateOf("") }
    fun submit() {
        if (draft.isNotBlank() && !vm.streaming) {
            vm.send(draft)
            draft = ""
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 60.dp),
            placeholder = { Text("Message Claude…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            maxLines = 3,
        )
        Spacer(Modifier.width(16.dp))
        Button(
            onClick = ::submit,
            enabled = draft.isNotBlank() && !vm.streaming,
            modifier = Modifier.height(60.dp),
        ) {
            Text(if (vm.streaming) "Working…" else "Send", fontSize = 20.sp)
        }
    }
}
