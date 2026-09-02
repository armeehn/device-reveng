package com.ripostelabs.carlauncher.carlib

/**
 * Door / hatch state from canbus2's `ACCORD_DOOR_INFO` broadcast, byte extra
 * `EventUtils.CAR_DOOR_DATA` (`CanDataParseBase.java:1260-1296`, sent at `:453-460`).
 *
 * Bit meaning is the vendor door overlay's (`DoorInfoWindow.java:211-291`): bit7 front-left,
 * bit6 front-right, bit5 rear-right, bit4 rear-left, bit3 tailgate, bit2 bonnet; a set bit is
 * an open door. UNVERIFIED: the rear pair. canbus2 swaps bits 5/4 when `Sys_Rear_Door_Tip_Set`
 * is on, so which physical rear door bit5 means depends on that SysVar.
 */
data class DoorState(
    val frontLeft: Boolean,
    val frontRight: Boolean,
    val rearRight: Boolean,
    val rearLeft: Boolean,
    val tailgate: Boolean,
    val bonnet: Boolean,
    val atMs: Long,
) {
    /** True when any door, the tailgate or the bonnet is open. */
    fun anyOpen(): Boolean = frontLeft || frontRight || rearRight || rearLeft || tailgate || bonnet

    companion object {
        private const val BIT_FRONT_LEFT = 0x80
        private const val BIT_FRONT_RIGHT = 0x40
        private const val BIT_REAR_RIGHT = 0x20
        private const val BIT_REAR_LEFT = 0x10
        private const val BIT_TAILGATE = 0x08
        private const val BIT_BONNET = 0x04

        /** Decode the raw `CAR_DOOR_DATA` byte (bits 1..0 are unused and ignored). */
        fun fromByte(raw: Int, atMs: Long): DoorState = DoorState(
            frontLeft = raw and BIT_FRONT_LEFT != 0,
            frontRight = raw and BIT_FRONT_RIGHT != 0,
            rearRight = raw and BIT_REAR_RIGHT != 0,
            rearLeft = raw and BIT_REAR_LEFT != 0,
            tailgate = raw and BIT_TAILGATE != 0,
            bonnet = raw and BIT_BONNET != 0,
            atMs = atMs,
        )
    }
}
