package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gateway contract, pinned to the decompile so a "helpful" rename cannot silently take a
 * signal away again. Action and extra strings are quoted from `EventUtils.java` / `CanUtils.java`
 * with the line cited; the pure decoders are exercised on the vendor's own encodings.
 */
class GatewayContractTest {

    // ---- UI mode (EventUtils.java:66,76,1235; EventService.java:14043-14087) ----

    @Test
    fun uiModeExtraIsTheGatewaysInt() {
        assertEquals("Extra_Day_Night_UiMode", GatewayHandshake.EXTRA_DAY_NIGHT_UIMODE)
        assertEquals(1, GatewayHandshake.UiMode.DAY.code)
        assertEquals(2, GatewayHandshake.UiMode.NIGHT.code)
        assertEquals(3, GatewayHandshake.UiMode.BY_TIME.code)
        assertEquals(0, GatewayHandshake.UiMode.HEADLAMPS.code)
    }

    @Test
    fun unknownUiModeCodeFollowsHeadlampsLikeTheGateway() {
        assertEquals(GatewayHandshake.UiMode.NIGHT, GatewayHandshake.UiMode.fromCode(2))
        // setDayNightMode's fallthrough: anything not 1/2/3 is headlamp mode.
        assertEquals(GatewayHandshake.UiMode.HEADLAMPS, GatewayHandshake.UiMode.fromCode(0))
        assertEquals(GatewayHandshake.UiMode.HEADLAMPS, GatewayHandshake.UiMode.fromCode(7))
    }

    // ---- Action strings: exact prefixes matter (com.choiceway vs com.szchoiceway) ----

    @Test
    fun actionPrefixesMatchTheDecompile() {
        assertEquals(
            "com.szchoiceway.eventcenter.EventUtils.ACTION_ACC_OPEN_CLOSE_EVT",
            CarEvents.ACTION_ACC_OPEN_CLOSE_EVT,
        )
        assertEquals(
            "com.szchoiceway.eventcenter.EventUtils.MCU_KEY_INFOR",
            CarEvents.MCU_KEY_INFOR_ACTION,
        )
        assertEquals(
            "com.szchoiceway.eventcenter.EventUtils.MCU_CAR_CAN_RADAR_INFO",
            CarEvents.MCU_CAR_CAN_RADAR_INFO,
        )
        assertEquals(
            "com.szchoiceway.eventcenter.EventUtils.ACCORD_DOOR_INFO",
            CarEvents.ACCORD_DOOR_INFO,
        )
        assertEquals(
            "com.choiceway.eventcenter.EventUtils.ZXW_CAN_WHEEL_TRACK_EVT_EXTRA",
            CarEvents.EXTRA_WHEEL_TRACK,
        )
        assertEquals(
            "com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT_EXTRA_STR",
            CarEvents.EXTRA_OUT_SIDE_TEMP_STR,
        )
        assertEquals("ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT", CarEvents.ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT)
        assertEquals("zxw_Launcher", CarEvents.EXTRA_LAUNCHER)
        assertEquals("com.choiceway.eventcenter.EventUtils.MCU_MSG_MAIL_VOL", CarEvents.MCU_MSG_MAIL_VOL)
        assertEquals("com.szchoiceway.eventcenter.LAMP_STATUS", CarEvents.LAMP_STATUS)
        assertEquals("com.szchoiceway.uiModeNightChanged", CarEvents.UI_MODE_NIGHT_CHANGED)
    }

    // ---- Volume push (EventService.java:3105-3125) ----

    @Test
    fun mailVolPacksMuteInBit7() {
        val loud = VolumeReading.fromMailVol(0x80 or 25, showWindow = true, atMs = 1L)
        assertEquals(25, loud.level)
        assertTrue(loud.muted)
        assertTrue(loud.showWindow)

        val quiet = VolumeReading.fromMailVol(7, showWindow = false, atMs = 2L)
        assertEquals(7, quiet.level)
        assertFalse(quiet.muted)
    }

    @Test
    fun mailVolMaxLevelSurvivesTheMask() {
        assertEquals(CarService.MAX_VOLUME, VolumeReading.fromMailVol(CarService.MAX_VOLUME, false, 0L).level)
        assertEquals(0, VolumeReading.fromMailVol(0x80, false, 0L).level)
    }

    // ---- Doors (DoorInfoWindow.java:211-291) ----

    @Test
    fun doorBitsDecodePerVendorOverlay() {
        val fl = DoorState.fromByte(0x80, 0L)
        assertTrue(fl.frontLeft)
        assertFalse(fl.frontRight)

        val all = DoorState.fromByte(0xFC, 0L)
        assertTrue(all.frontLeft && all.frontRight && all.rearRight && all.rearLeft && all.tailgate && all.bonnet)

        val bonnetOnly = DoorState.fromByte(0x04, 0L)
        assertTrue(bonnetOnly.bonnet)
        assertFalse(bonnetOnly.tailgate)
        assertTrue(bonnetOnly.anyOpen())
    }

    @Test
    fun unusedDoorBitsAreIgnored() {
        val closed = DoorState.fromByte(0x03, 0L)
        assertFalse(closed.anyOpen())
    }

    // ---- Steering (HiworldCanParseToyota.java:818-829) ----

    @Test
    fun wheelTrackSignBitAndMagnitude() {
        val right = SteeringReading.fromWheelTrack(12, 5L)
        assertEquals(12.0, right.degrees, 0.0)
        assertEquals(5L, right.atMs)

        val left = SteeringReading.fromWheelTrack(0x80 or 12, 5L)
        assertEquals(-12.0, left.degrees, 0.0)

        // Bits 0-6 only: 0xFF is -127, never -255.
        assertEquals(-127.0, SteeringReading.fromWheelTrack(0xFF, 0L).degrees, 0.0)
        assertEquals(0.0, SteeringReading.fromWheelTrack(0x80, 0L).degrees, 0.0)
    }

    // ---- Speed priority (CarEvents.pickSpeed) ----

    private val fresh = CarEvents.CAN_SPEED_STALE_MS - 1
    private val stale = CarEvents.CAN_SPEED_STALE_MS

    @Test
    fun trustedFreshCanOutranksGps() {
        assertEquals(
            42 to CarEvents.SpeedSource.CAN,
            CarEvents.pickSpeed(canKmh = 42, canAgeMs = fresh, gpsKmh = 40, trusted = true),
        )
    }

    @Test
    fun staleCanFallsBackToGps() {
        assertEquals(
            40 to CarEvents.SpeedSource.GPS,
            CarEvents.pickSpeed(canKmh = 42, canAgeMs = stale, gpsKmh = 40, trusted = true),
        )
    }

    @Test
    fun untrustedCanNeverWins() {
        assertEquals(
            40 to CarEvents.SpeedSource.GPS,
            CarEvents.pickSpeed(canKmh = 0, canAgeMs = fresh, gpsKmh = 40, trusted = false),
        )
    }

    @Test
    fun noSourceIsUnknownNotZero() {
        val (kmh, source) = CarEvents.pickSpeed(
            canKmh = GpsSpeedSource.SPEED_UNKNOWN,
            canAgeMs = fresh,
            gpsKmh = GpsSpeedSource.SPEED_UNKNOWN,
            trusted = true,
        )
        assertEquals(GpsSpeedSource.SPEED_UNKNOWN, kmh)
        assertEquals(CarEvents.SpeedSource.NONE, source)
    }

    @Test
    fun canSpeedIsHeldBackUntilVerified() {
        // The 2026-08-29 drive showed the 0x32 field is not road speed; flipping this is a
        // deliberate act after a steady-cruise capture, not a side effect.
        assertFalse(CarEvents.CAN_SPEED_TRUSTED)
    }
}
