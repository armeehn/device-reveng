package com.ripostelabs.claudecar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.ripostelabs.claudecar.ui.ChatScreen
import com.ripostelabs.claudecar.ui.ClaudeCarTheme

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClaudeCarTheme {
                ChatScreen(vm)
            }
        }
    }
}
