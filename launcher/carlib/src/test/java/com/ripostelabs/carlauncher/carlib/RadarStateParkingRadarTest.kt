package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On Riposte OS 0.2 the radar arrives as the relayed `0x41` frame ([CanSignal.ParkingRadar]),
 * not as canbus2's broadcast. The decoder stores `level × 30` cm (`HiworldCanDecoder.radar`), the
 * same code canbus2 stores (`HiworldCanParseToyota.java:903-921`), so the bands must come out
 * identical to [RadarState.fromRadarData] on the equivalent broadcast frame.
 */
class RadarStateParkingRadarTest {

    @Test
    fun codesBecomeBandsRearAndFront() {
        val state = RadarState.fromParkingRadar(
            CanSignal.ParkingRadar(
                rearCm = listOf(30, 60, 90, 150),
                frontCm = listOf(null, 30, null, 60),
            ),
        )

        assertTrue(state.valid)
        assertEquals(listOf(5, 4, 3, 1), state.rear)
        assertEquals(listOf(0, 5, 0, 4), state.front)
    }

    @Test
    fun allClearIsValidAndEmptyOfObstacles() {
        val state = RadarState.fromParkingRadar(
            CanSignal.ParkingRadar(rearCm = List(4) { null }, frontCm = List(4) { null }),
        )

        assertTrue(state.valid)
        assertFalse(state.hasObstacle())
        assertEquals(List(4) { 0 }, state.rear)
    }

    @Test
    fun matchesTheBroadcastDecode() {
        val broadcast = RadarState.fromRadarData(
            byteArrayOf(1, 0xA0.toByte(), 30, 0xA0.toByte(), 90, 60, 0xA0.toByte(), 0xA0.toByte(), 150.toByte()),
        )
        val relay = RadarState.fromParkingRadar(
            CanSignal.ParkingRadar(rearCm = listOf(60, null, null, 150), frontCm = listOf(null, 30, null, 90)),
        )

        assertEquals(broadcast.front, relay.front)
        assertEquals(broadcast.rear, relay.rear)
    }
}
