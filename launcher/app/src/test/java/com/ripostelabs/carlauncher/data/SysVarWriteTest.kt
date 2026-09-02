package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The write order the vendor settings app uses: `changeSetup` on the bound gateway, provider
 * only when that is unavailable (`SystemPropertiesHelps.java:54-62`).
 */
class SysVarWriteTest {

    private val calls = mutableListOf<String>()

    private fun sink(name: String, ok: Boolean): SysVarSink = { k, v ->
        calls += "$name:$k=$v"
        ok
    }

    @Test
    fun boundGatewayWinsAndTheProviderIsNotTouched() {
        val route = persistSysVar("K", "1", sink("gw", true), sink("prov", true))

        assertEquals(WriteRoute.GATEWAY, route)
        assertEquals(listOf("gw:K=1"), calls)
    }

    @Test
    fun unboundGatewayFallsBackToTheProvider() {
        val route = persistSysVar("K", "1", sink("gw", false), sink("prov", true))

        assertEquals(WriteRoute.PROVIDER, route)
        assertEquals(listOf("gw:K=1", "prov:K=1"), calls)
    }

    @Test
    fun noGatewayHandleGoesStraightToTheProvider() {
        val route = persistSysVar("K", "1", null, sink("prov", true))

        assertEquals(WriteRoute.PROVIDER, route)
        assertEquals(listOf("prov:K=1"), calls)
    }

    @Test
    fun bothRefusedIsAFailure() {
        assertEquals(WriteRoute.FAILED, persistSysVar("K", "1", sink("gw", false), sink("prov", false)))
    }
}
