package com.ripostelabs.car

import org.junit.Assert.assertThrows
import org.junit.Test

class GateTest {

    private fun gate(vararg held: String) = Gate { it in held }

    @Test
    fun readPassesWithRead() {
        gate(READ_PERMISSION).enforce(Access.READ)
    }

    @Test
    fun readPassesWithControl() {
        gate(CONTROL_PERMISSION).enforce(Access.READ)
    }

    @Test
    fun readFailsWithNothing() {
        assertThrows(SecurityException::class.java) { gate().enforce(Access.READ) }
    }

    @Test
    fun controlFailsWithReadOnly() {
        assertThrows(SecurityException::class.java) { gate(READ_PERMISSION).enforce(Access.CONTROL) }
    }

    @Test
    fun controlPassesWithControl() {
        gate(CONTROL_PERMISSION).enforce(Access.CONTROL)
    }
}
