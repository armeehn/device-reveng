package com.ripostelabs.carlauncher.tuner

import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.RadioState
import kotlinx.coroutines.flow.StateFlow

/**
 * What [TunerHub] needs from the car link (RAV4-97). [CarService] is the only real one; tests
 * plug a recorder. Kept to the four tuner verbs so the binder has nothing else to leak.
 */
interface TunerPort {
    val state: StateFlow<RadioState>
    /** Ticks when another source took the MCU (CarService.sourceLost). */
    val sourceLost: StateFlow<Long>
    fun claim(): Boolean
    fun release()
    fun isClaimed(): Boolean
    fun sendKey(key: Int)
    fun tune(freq: Int, fm: Boolean)
}

/** The launcher's link: owner path writes OP_RADIO_KEY / OP_USER_FREQ, binder path asks the gateway. */
class CarTunerPort(private val car: CarService) : TunerPort {
    override val state: StateFlow<RadioState> get() = car.radioState.state
    override val sourceLost: StateFlow<Long> get() = car.sourceLost

    override fun claim(): Boolean {
        car.claimRadio()
        return car.isRadioClaimed()
    }

    override fun release() = car.releaseRadio()
    override fun isClaimed(): Boolean = car.isRadioClaimed()
    override fun sendKey(key: Int) = car.sendRadioKey(key)
    override fun tune(freq: Int, fm: Boolean) = car.sendUserFreq(freq, fm)
}
