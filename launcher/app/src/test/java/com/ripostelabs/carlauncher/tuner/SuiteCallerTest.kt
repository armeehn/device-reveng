package com.ripostelabs.carlauncher.tuner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gate on every ITuner transaction: our signature or a com.ripostelabs.* package, nothing else. */
class SuiteCallerTest {

    private val me = 10042

    @Test
    fun suitePackagePasses() {
        assertTrue(SuiteCaller.allowed(10077, me, listOf("com.ripostelabs.radio"), sameSignature = false))
    }

    @Test
    fun ownSignaturePasses() {
        assertTrue(SuiteCaller.allowed(10077, me, listOf("org.example.debugtwin"), sameSignature = true))
        assertTrue(SuiteCaller.allowed(me, me, emptyList(), sameSignature = false))
    }

    @Test
    fun strangerIsRefused() {
        assertFalse(SuiteCaller.allowed(10077, me, listOf("com.szchoiceway.radio", "com.example.x"), sameSignature = false))
        assertFalse(SuiteCaller.allowed(10077, me, emptyList(), sameSignature = false))
    }
}
