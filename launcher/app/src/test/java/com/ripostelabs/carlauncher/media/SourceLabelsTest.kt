package com.ripostelabs.carlauncher.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLabelsTest {
    @Test fun zlinkReadsAsCarPlay() {
        assertEquals("CarPlay", SourceLabels.of("com.zjinnova.zlink"))
    }

    @Test fun onlyTheReceiverItselfIsRenamed() {
        // The helper packages keep their own labels; the map is explicit, not a prefix.
        assertNull(SourceLabels.of("com.zjinnova.netshare"))
        assertNull(SourceLabels.of("com.ripostelabs.music"))
    }

    @Test fun carPlayIsTheReceiverPackageOnly() {
        assertTrue(SourceLabels.isCarPlay("com.zjinnova.zlink"))
        assertFalse(SourceLabels.isCarPlay("com.zjinnova.netshare"))
        assertFalse(SourceLabels.isCarPlay(null))
    }

    @Test fun projectionTitlesAreTheGatewaysOwn() {
        // ZlinkManage.setCarPlayValidModeInfor spells it "Carplay", lower-case p.
        assertTrue(SourceLabels.isProjection("Carplay"))
        assertTrue(SourceLabels.isProjection("Android Auto"))
        assertFalse(SourceLabels.isProjection("CarPlay"))
        assertFalse(SourceLabels.isProjection("Bluetooth"))
        assertFalse(SourceLabels.isProjection(null))
    }
}
