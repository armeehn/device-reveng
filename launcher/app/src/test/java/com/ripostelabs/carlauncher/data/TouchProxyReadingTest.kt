package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchProxyReadingTest {

    @Test
    fun `vendor slot never complains`() {
        val r = TouchProxy.read(ownerActive = false, deviceNames = listOf("TouchScreenZXW"))
        assertTrue(r.ok)
        assertEquals(TouchProxy.VENDOR_TITLE, r.title)
    }

    @Test
    fun `owner slot with the proxy device is up`() {
        val r = TouchProxy.read(ownerActive = true, deviceNames = listOf("qpnp_pon", "Riposte Touch"))
        assertTrue(r.ok)
        assertEquals(TouchProxy.UP_TITLE, r.title)
    }

    @Test
    fun `owner slot without the proxy names the symptom`() {
        val r = TouchProxy.read(ownerActive = true, deviceNames = listOf("TouchScreenZXW"))
        assertFalse(r.ok)
        assertEquals(TouchProxy.DOWN_TITLE, r.title)
        assertEquals(TouchProxy.DOWN_DETAIL, r.detail)
    }
}
