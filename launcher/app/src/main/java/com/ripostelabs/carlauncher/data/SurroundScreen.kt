package com.ripostelabs.carlauncher.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the 360 view is up. The AIS client serves one device per process, and a new session
 * closes the one holding it ([AisCamera]), so the reverse feed kept warm in the background lets
 * go while this is shown and opens afresh after, instead of being closed under it.
 */
object SurroundScreen {
    private val shown = MutableStateFlow(false)

    val isShown: StateFlow<Boolean> = shown.asStateFlow()

    fun opened() {
        shown.value = true
    }

    fun closed() {
        shown.value = false
    }
}
