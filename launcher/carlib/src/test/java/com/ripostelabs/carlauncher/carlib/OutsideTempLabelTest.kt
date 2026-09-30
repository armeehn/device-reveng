package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/** RAV4-198: the CAN box's outside air, written the way canbus2 wrote it ("23℃"). */
class OutsideTempLabelTest {

    @Test
    fun `whole degrees drop the decimal`() {
        assertEquals("23℃", ClimateState.outsideLabel(23.0))
        assertEquals("-4℃", ClimateState.outsideLabel(-4.0))
    }

    @Test
    fun `half degrees keep one decimal`() {
        assertEquals("12.5℃", ClimateState.outsideLabel(12.5))
        assertEquals("-0.5℃", ClimateState.outsideLabel(-0.5))
    }
}
