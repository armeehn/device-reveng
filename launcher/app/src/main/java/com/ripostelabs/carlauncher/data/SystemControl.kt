package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.AecPath
import com.ripostelabs.carlauncher.carlib.Hotspot
import com.ripostelabs.carlauncher.carlib.McuPort
import com.ripostelabs.carlauncher.carlib.MicGain
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * RAV4-216: the hotspot and the language through the car service, which sets both as the
 * system uid. Without a service at 9 nothing is sent and the caller opens Android's page.
 *
 *   shade / Settings ──▶ SystemControl ──▶ McuPort ──▶ car service ──▶ TetheringManager
 *                                                                 └──▶ system locale
 */
class SystemControl(private val port: () -> McuPort?) {

    /** Whether the car service takes the hotspot and the language. */
    val routed: Boolean
        get() = port()?.controlsSystem == true

    /** The hotspot as the system holds it; null without the service. Blocking IPC. */
    fun hotspot(): Hotspot? = port()?.hotspot()

    /** The hotspot, read again every [POLL_MS] while collected. */
    fun hotspotState(): Flow<Hotspot?> = flow {
        while (true) {
            emit(hotspot())
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    /** Flips the hotspot. False when nothing was sent. Blocking IPC. */
    fun toggleHotspot(): Boolean {
        val owner = port() ?: return false
        val now = owner.hotspot() ?: return false
        val next = if (now == Hotspot.ON) Hotspot.OFF else Hotspot.ON
        return owner.setHotspot(next)
    }

    /** Sets the system language, a BCP 47 tag. False when nothing was sent. Blocking IPC. */
    fun setLanguage(tag: String): Boolean = port()?.setLanguage(tag) == true

    /** RAV4-184: whether the car service takes the echo delays and the mic gain (service 10). */
    val callAudioRouted: Boolean
        get() = port()?.controlsCallAudio == true

    /** RAV4-184: the echo-cancel delay in ms; null when unset or without the service. Blocking IPC. */
    fun aecDelay(path: AecPath): Int? = port()?.aecDelay(path)

    /** RAV4-184: sets the echo-cancel delay. False when nothing was sent. Blocking IPC. */
    fun setAecDelay(path: AecPath, ms: Int): Boolean = port()?.setAecDelay(path, ms) == true

    /** RAV4-184: the mic gain step; null when unset or without the service. Blocking IPC. */
    fun micGain(): MicGain? = port()?.micGain()

    /** RAV4-184: sets the mic gain. False when nothing was sent. Blocking IPC. */
    fun setMicGain(gain: MicGain): Boolean = port()?.setMicGain(gain) == true

    companion object {
        // Tethering takes about a second to come up, so a tap shows on the next read.
        private const val POLL_MS = 2_000L

        /** The picker's languages: one per tag, named in [display], by name. */
        fun languages(tags: Array<String>, display: Locale): List<Pair<String, String>> =
            tags.map { Locale.forLanguageTag(it) }
                .filter { it.language.isNotEmpty() }
                .distinctBy { it.toLanguageTag() }
                .map { it.toLanguageTag() to it.getDisplayName(display) }
                .sortedBy { it.second }
    }
}
