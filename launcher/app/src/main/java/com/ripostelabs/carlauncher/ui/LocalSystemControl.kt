package com.ripostelabs.carlauncher.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import com.ripostelabs.carlauncher.data.SystemControl

/** RAV4-216: the hotspot and language route; null in previews and tests. MainActivity provides it. */
val LocalSystemControl: ProvidableCompositionLocal<SystemControl?> = compositionLocalOf { null }
