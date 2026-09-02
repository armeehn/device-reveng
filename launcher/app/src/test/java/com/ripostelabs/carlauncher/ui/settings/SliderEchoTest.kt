package com.ripostelabs.carlauncher.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule the SysVar sliders live by: the finger wins while it is down.
 *
 * CarSettingsController republishes its snapshot from a ContentObserver that fires on any
 * provider change — the vendor UI or a CAN event, not only our own write — so a new value can
 * land mid-drag. It used to re-key the echo and snap the thumb back under the driver's finger.
 */
class SliderEchoTest {

    @Test
    fun `adopts an external value between drags`() {
        val echo = SliderEcho(40f)

        echo.sync(70f)

        assertEquals(70f, echo.position, 0f)
    }

    @Test
    fun `ignores an external value mid-drag`() {
        val echo = SliderEcho(40f)

        echo.drag(55f)
        echo.sync(10f) // vendor UI or CAN event republishes while the finger is down

        assertEquals(55f, echo.position, 0f)
    }

    @Test
    fun `commits the dragged position, not the republished one`() {
        val echo = SliderEcho(40f)

        echo.drag(55f)
        echo.sync(10f)
        echo.drag(80f)

        assertEquals(80f, echo.release(), 0f)
    }

    @Test
    fun `resumes external updates after the finger lifts`() {
        val echo = SliderEcho(40f)

        echo.drag(80f)
        echo.release()
        echo.sync(25f) // the snapshot catching up, or someone else's write

        assertEquals(25f, echo.position, 0f)
    }

    @Test
    fun `a stepper jump moves the thumb`() {
        val echo = SliderEcho(40f)

        echo.set(41f)

        assertEquals(41f, echo.position, 0f)
    }
}
