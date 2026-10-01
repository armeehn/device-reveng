package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** RAV4-199: the bind, grant and configure steps of adding a home widget. */
class HomeWidgetFlowTest {

    @Test
    fun `a platform-signed launcher binds straight away`() {
        assertEquals(BindStage.DONE, HomeWidgetFlow.next(BindStage.BIND, Outcome.OK, Setup.NONE))
    }

    @Test
    fun `a refused bind asks the user for the grant`() {
        assertEquals(BindStage.GRANT, HomeWidgetFlow.next(BindStage.BIND, Outcome.REFUSED, Setup.NONE))
        assertEquals(BindStage.GRANT, HomeWidgetFlow.next(BindStage.BIND, Outcome.REFUSED, Setup.CONFIGURE))
    }

    @Test
    fun `a granted bind goes on to the widget's own setup`() {
        assertEquals(BindStage.CONFIGURE, HomeWidgetFlow.next(BindStage.GRANT, Outcome.OK, Setup.CONFIGURE))
        assertEquals(BindStage.CONFIGURE, HomeWidgetFlow.next(BindStage.BIND, Outcome.OK, Setup.CONFIGURE))
        assertEquals(BindStage.DONE, HomeWidgetFlow.next(BindStage.GRANT, Outcome.OK, Setup.NONE))
    }

    @Test
    fun `a refused grant or setup cancels`() {
        assertEquals(BindStage.CANCELLED, HomeWidgetFlow.next(BindStage.GRANT, Outcome.REFUSED, Setup.NONE))
        assertEquals(BindStage.CANCELLED, HomeWidgetFlow.next(BindStage.CONFIGURE, Outcome.REFUSED, Setup.CONFIGURE))
    }

    @Test
    fun `a finished setup is done`() {
        assertEquals(BindStage.DONE, HomeWidgetFlow.next(BindStage.CONFIGURE, Outcome.OK, Setup.CONFIGURE))
    }

    @Test
    fun `finished stages stay put`() {
        assertEquals(BindStage.DONE, HomeWidgetFlow.next(BindStage.DONE, Outcome.REFUSED, Setup.NONE))
        assertEquals(BindStage.CANCELLED, HomeWidgetFlow.next(BindStage.CANCELLED, Outcome.OK, Setup.NONE))
    }

    @Test
    fun `the picker lists widgets by label, then app`() {
        val rows = listOf(
            WidgetChoice("com.b", "b.W", "Clock", "Zed"),
            WidgetChoice("com.a", "a.W", "Clock", "Alpha"),
            WidgetChoice("com.c", "c.W", "Agenda", "Cal"),
        )
        val sorted = HomeWidgetFlow.order(rows).map { it.pkg }
        assertEquals(listOf("com.c", "com.a", "com.b"), sorted)
    }
}
