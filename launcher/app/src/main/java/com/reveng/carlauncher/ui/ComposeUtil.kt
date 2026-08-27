package com.reveng.carlauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * Lifecycle-aware [Flow] collection with an explicit initial value. Works for both
 * StateFlow and SharedFlow and stops collecting when the UI is not started.
 */
@Composable
fun <T> Flow<T>.collectAsStateSafe(initial: T): State<T> =
    collectAsStateWithLifecycle(initialValue = initial)
