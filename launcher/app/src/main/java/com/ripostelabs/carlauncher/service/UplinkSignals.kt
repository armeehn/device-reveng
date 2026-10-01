package com.ripostelabs.carlauncher.service

import com.ripostelabs.carlauncher.carlib.BtCarKit
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.Gear
import com.ripostelabs.carlauncher.carlib.NoisePlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * UplinkSignals: the car state [UplinkService] needs, published by the activity that owns
 * [CarEvents]. The service must not build a second CarEvents (two readers on the car link split
 * the stream), so the launcher hands it a snapshot instead. Null until the first one arrives,
 * which the service reads as "do not capture".
 */
object UplinkSignals {

    private val _car = MutableStateFlow<NoisePlan.Car?>(null)
    val car: StateFlow<NoisePlan.Car?> = _car.asStateFlow()

    /** Whether any door is open, if the car reports doors; for the sidecar only. */
    @Volatile
    var doorsOpen: Boolean? = null
        private set

    /** Whether the A/C compressor is on, if the climate panel reports; for the sidecar only. */
    @Volatile
    var acOn: Boolean? = null
        private set

    fun bind(scope: CoroutineScope, car: CarEvents, kit: BtCarKit?) {
        val kitCalls = kit?.snapshot ?: flowOf(null)
        val gearNow = combine(car.reverse, car.canGear) { wire, can -> if (wire) Gear.REVERSE else can }
        val inCall = combine(car.vendorBt, kitCalls, car.carplayState) { bt, k, cp ->
            bt.inCall == true || cp.inCall || k?.calls?.any { it.inProgress } == true
        }
        val carPlay = combine(car.carplayState, car.zlinkConnected) { cp, z -> cp.connected || z }

        scope.launch {
            combine(car.accOn, car.speedKmh, gearNow, inCall, carPlay) { acc, speed, gear, call, cp ->
                NoisePlan.Car(
                    acc = if (acc) NoisePlan.Acc.ON else NoisePlan.Acc.OFF,
                    speedKmh = speed,
                    gear = gear,
                    call = if (call) NoisePlan.Call.ACTIVE else NoisePlan.Call.NONE,
                    carPlay = if (cp) NoisePlan.Session.ACTIVE else NoisePlan.Session.NONE,
                    otherRecorders = 0,
                    fanLevel = 0,
                    fanMax = 0,
                )
            }.combine(car.climate) { base, climate ->
                acOn = climate?.acOn
                base.copy(fanLevel = climate?.fanLevel ?: 0, fanMax = climate?.fanMax ?: 0)
            }.collect { _car.value = it }
        }
        scope.launch {
            car.doors.collect { d ->
                doorsOpen = d?.let { it.frontLeft || it.frontRight || it.rearLeft || it.rearRight || it.tailgate }
            }
        }
    }
}
