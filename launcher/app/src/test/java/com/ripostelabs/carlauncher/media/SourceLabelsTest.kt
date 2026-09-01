package com.ripostelabs.carlauncher.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
