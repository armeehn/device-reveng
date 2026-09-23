package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the gateway wrote for `Sys_backcar_Video_Type`: "v<row>" to the PR2000 decoder node
 * (BackcarEvent.java:1392-1408, SignalView.java:87-91), rows 0..8 being the vendor picker
 * (BackcarSignalTypeSet.java:61-93). `Sys_6752_Backcar_Video_Type` has no reader in the
 * gateway and `rn6752_mode` is never written (CamerasSignalDetection.java:34), so it maps
 * to nothing. On 0.2 the write goes through `persist.riposte.camera.mode` and init; the
 * direct write is the fallback.
 */
class ReverseCameraDecoderTest {

    private val ok = RootShell.Result(0, emptyList(), emptyList())
    private val refused = RootShell.Result(1, emptyList(), emptyList())

    @Test
    fun pickerRowsAreTheVendorsNine() {
        // BackcarSignalTypeSet.java:61-93: the row each checkbox writes.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8), ReverseCameraDecoder.VIDEO_TYPES.map { it.first })
        assertEquals("Auto", ReverseCameraDecoder.VIDEO_TYPES[0].second)
        assertEquals("CVBS NTSC", ReverseCameraDecoder.VIDEO_TYPES[1].second)
        assertEquals("CVBS PAL", ReverseCameraDecoder.VIDEO_TYPES[2].second)
        assertEquals("AHD 720p 25 Hz", ReverseCameraDecoder.VIDEO_TYPES[3].second)
        assertEquals("AHD 1080p 25 Hz", ReverseCameraDecoder.VIDEO_TYPES[4].second)
        assertEquals("AHD 720p 60 Hz", ReverseCameraDecoder.VIDEO_TYPES[5].second)
        assertEquals("AHD 1080p 30 Hz", ReverseCameraDecoder.VIDEO_TYPES[6].second)
        assertEquals("AHD 720p 30 Hz", ReverseCameraDecoder.VIDEO_TYPES[7].second)
        assertEquals("CVBS PAL 60 Hz", ReverseCameraDecoder.VIDEO_TYPES[8].second)
    }

    @Test
    fun videoTypeMapsToTheDecoderState() {
        assertEquals("v0", ReverseCameraDecoder.signalState(SettingKeys.BACKCAR_VIDEO_TYPE, "0"))
        assertEquals("v3", ReverseCameraDecoder.signalState(SettingKeys.BACKCAR_VIDEO_TYPE, " 3 "))
        assertEquals("v8", ReverseCameraDecoder.signalState(SettingKeys.BACKCAR_VIDEO_TYPE, "8"))
    }

    @Test
    fun outOfBandOrForeignRowsMapToNothing() {
        assertNull(ReverseCameraDecoder.signalState(SettingKeys.BACKCAR_VIDEO_TYPE, "9"))
        assertNull(ReverseCameraDecoder.signalState(SettingKeys.BACKCAR_VIDEO_TYPE, "-1"))
        assertNull(ReverseCameraDecoder.signalState(SettingKeys.BACKCAR_VIDEO_TYPE, "auto"))
        assertNull(ReverseCameraDecoder.signalState(SettingKeys.BACKCAR_6752_VIDEO_TYPE, "1"))
    }

    @Test
    fun modeCommandSetsThePropertyAndReadsItBack() {
        assertEquals(
            "setprop persist.riposte.camera.mode 3 && [ \"\$(getprop persist.riposte.camera.mode)\" = 3 ]",
            ReverseCameraDecoder.modeCommand(3),
        )
    }

    @Test
    fun directCommandUnlocksTheNodeThenWritesWhereItExists() {
        val cmd = ReverseCameraDecoder.command("v3")

        // BackcarEvent.java:1371 before any decoder write.
        assertTrue(cmd.startsWith("(setprop sys.pr2000.writable 1;"))
        assertTrue(cmd.contains("'/sys/pr2000/pr2000'"))
        assertTrue(cmd.contains("'/sys/devices/platform/soc/5c0c000.qcom,cci/5c0c000.qcom,cci:qcom,camera@0/pr2000'"))
        assertTrue(cmd.contains("printf %s 'v3' >"))
    }

    @Test
    fun withoutRootNothingRuns() {
        val ran = mutableListOf<String>()

        ReverseCameraDecoder.apply(SettingKeys.BACKCAR_VIDEO_TYPE, "3", rootAvailable = false) { ran += it; ok }
        ReverseCameraDecoder.apply(SettingKeys.BACKCAR_6752_VIDEO_TYPE, "1", rootAvailable = true) { ran += it; ok }

        assertEquals(emptyList<String>(), ran)
    }

    @Test
    fun withRootThePropertyIsEnough() {
        val ran = mutableListOf<String>()

        ReverseCameraDecoder.apply(SettingKeys.BACKCAR_VIDEO_TYPE, "3", rootAvailable = true) { ran += it; ok }

        assertEquals(listOf(ReverseCameraDecoder.modeCommand(3)), ran)
    }

    @Test
    fun refusedPropertyFallsBackToTheDirectWrite() {
        val ran = mutableListOf<String>()

        ReverseCameraDecoder.apply(SettingKeys.BACKCAR_VIDEO_TYPE, "3", rootAvailable = true) {
            ran += it
            if (it == ReverseCameraDecoder.modeCommand(3)) refused else ok
        }

        assertEquals(listOf(ReverseCameraDecoder.modeCommand(3), ReverseCameraDecoder.command("v3")), ran)
    }
}
