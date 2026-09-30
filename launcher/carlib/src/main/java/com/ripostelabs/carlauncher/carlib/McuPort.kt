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

    /** RAV4-156: whether POWER runs the power-off burst. False where the owner cannot take it. */
    fun setPowerKey(mode: PowerKeyMode): Boolean = false
}
