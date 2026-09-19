package com.ripostelabs.carlauncher.data

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Riposte OS installs the suite under /product, so FLAG_SYSTEM alone must not hide it. */
class SystemAppsTest {

    @Test
    fun `flagged system app folds into the System folder`() {
        assertTrue(SystemApps.isSystem("com.android.calculator2", ApplicationInfo.FLAG_SYSTEM))
    }

    @Test
    fun `user app stays on the home grid`() {
        assertFalse(SystemApps.isSystem("org.example.userapp", 0))
    }

    @Test
    fun `curated system launcher stays on the home grid`() {
        assertFalse(SystemApps.isSystem("com.android.settings", ApplicationInfo.FLAG_SYSTEM))
    }

    @Test
    fun `vendor prefix folds even without the flag`() {
        assertTrue(SystemApps.isSystem("com.szchoiceway.testtools", 0))
    }

    @Test
    fun `suite member installed as a system app stays on the home grid`() {
        RiposteSuite.APPS.forEach {
            assertFalse(it.packageName, SystemApps.isSystem(it.packageName, ApplicationInfo.FLAG_SYSTEM))
        }
    }
}
