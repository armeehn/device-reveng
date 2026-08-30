package com.ripostelabs.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * v0.4.7.1 — when the current ignition session started.
 *
 * Owned at activity scope, not inside the Dashboard's composition: the session tile used to
 * `remember` its own start time, so merely opening the Dashboard reset the timer. The start is
 * set once on the ACC OFF→ON transition (or at launcher start, when ACC is already on — the
 * earliest moment we can know about) and cleared on ACC off; the tile only formats it.
 */
class IgnitionSession(
    scope: CoroutineScope,
    accOn: Flow<Boolean>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _startedAt = MutableStateFlow<Long?>(null)

    /** Epoch millis the session began, or null while ACC is off. */
    val startedAt: StateFlow<Long?> = _startedAt.asStateFlow()

    init {
        scope.launch {
            accOn.collect { on ->
                when {
                    !on -> _startedAt.value = null
                    _startedAt.value == null -> _startedAt.value = clock()
                }
            }
        }
    }
}
