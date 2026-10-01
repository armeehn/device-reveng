package com.ripostelabs.carlauncher.carlib

import kotlinx.coroutines.flow.StateFlow

/**
 * What the launcher asks of the MCU link, whoever owns the port:
 *
 *     McuOwner        this process holds the port (images without the car service)
 *     RemoteMcuOwner  the car service holds it; calls and events cross ICarService
 *
 * Exactly one of the two runs, never both (os/CARHAL.md "One owner").
 */
interface McuPort {
    val status: StateFlow<McuOwner.Status>

    /** The last source set, so a wake can resume it; null before the first. */
    val lastMode: McuOwnerProtocol.Mode?

    /** Open the link (boot, ACC wake). */
    fun start()

    /** Close the link (ACC sleep). */
    fun stop()

    /** This process is done with the link: close it if ours, let go of it if the service's. */
    fun release()

    fun send(frame: ByteArray)

    fun setMode(mode: McuOwnerProtocol.Mode): Boolean

    fun selectCar(profile: CarProfile)

    /** RAV4-170: the last playable source kept across boots; null where the owner keeps none. */
    val resumeMode: McuOwnerProtocol.Mode?
        get() = null

    /** RAV4-169: the system night mode for every app. False where the owner cannot set it. */
    fun setNightMode(mode: SystemNight): Boolean = false

    /** RAV4-156: whether POWER runs the power-off burst. False where the owner cannot take it. */
    fun setPowerKey(mode: PowerKeyMode): Boolean = false

    /** RAV4-216: whether the hotspot and the language go through this owner. */
    val controlsSystem: Boolean
        get() = false

    /** RAV4-216: turns the Wi-Fi hotspot on or off. False where the owner cannot. */
    fun setHotspot(state: Hotspot): Boolean = false

    /** RAV4-216: the hotspot as the system holds it; null where the owner cannot tell. */
    fun hotspot(): Hotspot? = null

    /** RAV4-216: the system language, a BCP 47 tag. False where the owner cannot set it. */
    fun setLanguage(tag: String): Boolean = false

    /** RAV4-184: whether the echo delays and the mic gain go through this owner. */
    val controlsCallAudio: Boolean
        get() = false

    /** RAV4-184: the echo-cancel delay for [path] in ms. False where the owner cannot set it. */
    fun setAecDelay(path: AecPath, ms: Int): Boolean = false

    /** RAV4-184: the echo-cancel delay for [path] in ms; null when unset or the owner cannot tell. */
    fun aecDelay(path: AecPath): Int? = null

    /** RAV4-184: the mic gain step. False where the owner cannot set it. */
    fun setMicGain(gain: MicGain): Boolean = false

    /** RAV4-184: the mic gain step; null when unset or the owner cannot tell. */
    fun micGain(): MicGain? = null
}
