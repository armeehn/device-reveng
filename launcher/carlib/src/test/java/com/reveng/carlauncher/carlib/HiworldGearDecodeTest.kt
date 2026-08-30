package com.reveng.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the 0x1A PRNDL mapping recovered from the 2026-08-29 drive capture: p[5] is the gear code
 * (0=D, 1=P, 2=N, 3=R) and anything else is [Gear.UNKNOWN] rather than a wrong gear. The observed
 * pairs were P=(01,01) R=(03,03) D=(01,00) N=(01,02); the raw bytes are preserved for diagnostics.
 */
class HiworldGearDecodeTest {

    /** A minimal 0x1A payload: p[1]=coarse flag, p[5]=gear code, p[9:10]=rpm mirror. */
    private fun frame(coarseB1: Int, gearB5: Int, rpm: Int = 0): ByteArray {
        val p = ByteArray(11)
        p[1] = coarseB1.toByte()
        p[5] = gearB5.toByte()
        p[9] = ((rpm shr 8) and 0xFF).toByte()
        p[10] = (rpm and 0xFF).toByte()
        return p
    }

    private fun decode(coarseB1: Int, gearB5: Int) =
        HiworldCanDecoder.decodePayload(0x1A, frame(coarseB1, gearB5)) as CanSignal.RpmGearMirror

    @Test
    fun mapsAllFourGears() {
        assertEquals(Gear.PARK, decode(0x01, 0x01).gear)
        assertEquals(Gear.REVERSE, decode(0x03, 0x03).gear)
        assertEquals(Gear.DRIVE, decode(0x01, 0x00).gear)
        assertEquals(Gear.NEUTRAL, decode(0x01, 0x02).gear)
    }

    @Test
    fun unmappedCodeIsUnknownNotAWrongGear() {
        assertEquals(Gear.UNKNOWN, decode(0x01, 0x04).gear)
        assertEquals(Gear.UNKNOWN, decode(0x01, 0xFF).gear)
    }

    @Test
    fun rawBytesArePreservedForDiagnostics() {
        val sig = decode(0x03, 0x03)
        assertEquals(0x03, sig.gearRawB1)
        assertEquals(0x03, sig.gearRawB5)
    }
}
