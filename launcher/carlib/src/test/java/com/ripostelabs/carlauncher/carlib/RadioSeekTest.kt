package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Seek ran through tuned stations in the car (RAV4-147). The owner path set the tuner up unlike
 * stock: RDS on and the Europe band plan. These pin what the MCU hears at boot and before a seek.
 */
class RadioSeekTest {

    /** Stock's first boot sends RDS off: `SYS_RDS_OnOff` defaults to "0" (EventService.java:6620, :3792). */
    @Test
    fun startupSendsRdsOffLikeStock() {
        assertFalse(McuOwnerProtocol.StartupConfig().rds)
    }

    /** The car is in Canada: zone 1, AM on the 10 kHz grid up to 1710, FM to 107.9. */
    @Test
    fun startupZoneIsNorthAmerica() {
        val zone = RadioZone.of(McuOwnerProtocol.StartupConfig().radioZone)

        assertEquals(10, zone.am.step)
        assertEquals(1710, zone.am.max)
        assertEquals(10790, zone.fm.max)
    }

    /** TA on makes an RDS tuner stop only on traffic stations, and North America has none. */
    @Test
    fun seekClearsTrafficFirstInNorthAmerica() {
        val state = RadioState(ta = true, zone = 1)

        assertEquals(listOf(CarService.RADIO_KEY_TA, CarService.RADIO_KEY_SEEK_UP), CarService.seekKeys(CarService.RADIO_KEY_SEEK_UP, state))
        assertEquals(listOf(CarService.RADIO_KEY_TA, CarService.RADIO_KEY_SEEK_DOWN), CarService.seekKeys(CarService.RADIO_KEY_SEEK_DOWN, state))
    }

    @Test
    fun seekWithTrafficOffIsTheKeyAlone() {
        assertEquals(listOf(CarService.RADIO_KEY_SEEK_UP), CarService.seekKeys(CarService.RADIO_KEY_SEEK_UP, RadioState(zone = 1)))
    }

    /** In Europe TA-only seek is the feature: left alone. */
    @Test
    fun europeKeepsTrafficSeek() {
        assertEquals(listOf(CarService.RADIO_KEY_SEEK_UP), CarService.seekKeys(CarService.RADIO_KEY_SEEK_UP, RadioState(ta = true, zone = 0)))
    }

    /** Only seeks are guarded; the TA key itself and the rest pass through. */
    @Test
    fun otherKeysPassThrough() {
        val state = RadioState(ta = true, zone = 1)

        assertEquals(listOf(CarService.RADIO_KEY_TA), CarService.seekKeys(CarService.RADIO_KEY_TA, state))
        assertEquals(listOf(CarService.RADIO_KEY_STEP_UP), CarService.seekKeys(CarService.RADIO_KEY_STEP_UP, state))
    }
}
