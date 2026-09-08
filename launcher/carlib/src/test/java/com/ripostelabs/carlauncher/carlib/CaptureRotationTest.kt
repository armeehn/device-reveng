package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both bounds matter and they fail in opposite directions. Rolling too eagerly shreds a drive into
 * useless fragments; never dropping the oldest fills the device on a long ignition cycle. The
 * negative controls pin the first, the deletion tests pin the second.
 */
class CaptureRotationTest {

    @Test
    fun `the first file is written before anything rolls`() {
        val rotation = CaptureRotation(maxBytesPerFile = 100, maxFiles = 3)

        assertEquals("can-0.log", rotation.currentName())
        assertFalse(rotation.shouldRoll(99))
    }

    @Test
    fun `reaching the size opens the next file`() {
        val rotation = CaptureRotation(maxBytesPerFile = 100, maxFiles = 3)

        assertTrue(rotation.shouldRoll(100))
        rotation.roll()

        assertEquals("can-1.log", rotation.currentName())
    }

    @Test
    fun `nothing is deleted while the set is filling`() {
        val rotation = CaptureRotation(maxBytesPerFile = 10, maxFiles = 3)

        assertNull(rotation.roll())
        assertNull(rotation.roll())
    }

    @Test
    fun `the oldest file is dropped once the set is full`() {
        val rotation = CaptureRotation(maxBytesPerFile = 10, maxFiles = 3)

        rotation.roll()
        rotation.roll()

        // Opening the fourth file evicts the first.
        assertEquals("can-0.log", rotation.roll())
        assertEquals("can-3.log", rotation.currentName())
    }

    @Test
    fun `eviction keeps exactly the most recent window`() {
        val rotation = CaptureRotation(maxBytesPerFile = 10, maxFiles = 2)
        val deleted = mutableListOf<String>()

        repeat(5) { rotation.roll()?.let { name -> deleted += name } }

        // Five rolls with two kept: files 0-3 are evicted, leaving exactly can-4 and can-5.
        assertEquals(listOf("can-0.log", "can-1.log", "can-2.log", "can-3.log"), deleted)
        assertEquals("can-5.log", rotation.currentName())
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a file below the size never rolls`() {
        val rotation = CaptureRotation(maxBytesPerFile = 1_000, maxFiles = 3)

        // Shredding a drive into fragments is as bad as filling the disk.
        assertFalse(rotation.shouldRoll(0))
        assertFalse(rotation.shouldRoll(999))
        assertEquals("can-0.log", rotation.currentName())
    }

    @Test
    fun `the default budget is bounded and non-trivial`() {
        val rotation = CaptureRotation()

        assertFalse(rotation.shouldRoll(CaptureRotation.MAX_BYTES_PER_FILE - 1))
        assertTrue(rotation.shouldRoll(CaptureRotation.MAX_BYTES_PER_FILE))
    }
}
