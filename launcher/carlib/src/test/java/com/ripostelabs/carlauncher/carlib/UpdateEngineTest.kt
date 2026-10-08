package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The root shell lines that drive update_engine, and the reading of its status. The headers come
 * from the estate, so the command must never carry anything but base64 and digits from them.
 */
class UpdateEngineTest {

    private val headers = "FILE_HASH=qg+/==\nFILE_SIZE=1900000000\nMETADATA_HASH=bWV0YQ==\nMETADATA_SIZE=12\n"

    @Test
    fun `apply hands update_engine the file, its size and the four headers`() {
        val cmd = UpdateEngine.apply("/data/ota_package/payload.bin", 1_900_000_000L, headers)!!
        assertEquals(
            "restorecon -RF /data/ota_package; update_engine_client --update " +
                "--payload=file:///data/ota_package/payload.bin --offset=0 --size=1900000000 " +
                "--headers='FILE_HASH=qg+/==\nFILE_SIZE=1900000000\nMETADATA_HASH=bWV0YQ==\nMETADATA_SIZE=12'",
            cmd,
        )
    }

    @Test
    fun `headers with anything but base64 and digits are refused`() {
        assertNull(UpdateEngine.apply("/data/ota_package/payload.bin", 1L, "FILE_HASH=';reboot;'\nFILE_SIZE=1\n"))
        assertNull(UpdateEngine.apply("/data/ota_package/payload.bin", 1L, "EXTRA=1\n$headers"))
        assertNull(UpdateEngine.apply("/data/ota_package/payload.bin", 1L, "FILE_HASH=qg==\n"))
    }

    @Test
    fun `the last status line wins`() {
        val out = """
            onStatusUpdate(UPDATE_STATUS_IDLE (0), 0)
            onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 0.25)
            onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 0.5)
        """.trimIndent()
        assertEquals(UpdateEngine.Progress(UpdateEngine.Status.DOWNLOADING, 0.5f), UpdateEngine.status(out))
    }

    @Test
    fun `status is read by its number, not its name`() {
        val p = UpdateEngine.status("onStatusUpdate(UPDATED_NEED_REBOOT (6), 1)")!!
        assertEquals(UpdateEngine.Status.UPDATED_NEED_REBOOT, p.status)
        assertEquals(UpdateEngine.Status.UNKNOWN, UpdateEngine.status("onStatusUpdate(X (42), 0)")!!.status)
    }

    @Test
    fun `no status line is no status`() {
        assertNull(UpdateEngine.status(""))
        assertNull(UpdateEngine.status("update_engine_client: not found"))
    }

    @Test
    fun `a failed apply names its error code`() {
        assertEquals(26, UpdateEngine.error("onPayloadApplicationComplete(ErrorCode::kDownloadMetadataSignatureMismatch (26))"))
        assertNull(UpdateEngine.error("onStatusUpdate(UPDATE_STATUS_IDLE (0), 0)"))
        assertTrue(UpdateEngine.error("onPayloadApplicationComplete(ErrorCode::kSuccess (0))") == 0)
    }
}
