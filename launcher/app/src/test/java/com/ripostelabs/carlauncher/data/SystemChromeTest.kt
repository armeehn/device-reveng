package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.data.SystemChrome.Bars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The gateway rewrites `persist.sys.show_statusbar` to 1 every boot
 * (eventcenter `utils/SystemUtils.java:106-160`), so the suppression must bounce SystemUI
 * exactly when the prop was found in the wrong state, and never otherwise.
 */
class SystemChromeTest {

    private val restart = SystemChrome.RESTART_SYSTEMUI_CMD
    private val hide = "setprop ${SystemChrome.SHOW_STATUSBAR_PROP} 0"
    private val show = "setprop ${SystemChrome.SHOW_STATUSBAR_PROP} 1"

    @Test
    fun gatewayRevertedPropTriggersOneRestartAfterTheWrite() {
        val plan = SystemChrome.plan(Bars.LAUNCHER, "1")

        assertEquals(
            listOf(
                "cmd statusbar disable-for-setup true",
                "cmd statusbar collapse",
                hide,
                restart,
            ),
            plan,
        )
    }

    @Test
    fun routineReassertDoesNotBounceSystemUi() {
        val plan = SystemChrome.plan(Bars.LAUNCHER, "0")

        assertFalse(restart in plan)
        assertEquals(hide, plan.last())
    }

    @Test
    fun unknownPropIsTreatedAsReverted() {
        assertEquals(restart, SystemChrome.plan(Bars.LAUNCHER, null).last())
    }

    @Test
    fun restoringVendorBarsBouncesOnlyWhenHidden() {
        assertEquals(
            listOf("cmd statusbar disable-for-setup false", show, restart),
            SystemChrome.plan(Bars.VENDOR, "0"),
        )
        assertEquals(
            listOf("cmd statusbar disable-for-setup false", show),
            SystemChrome.plan(Bars.VENDOR, "1"),
        )
    }
}
