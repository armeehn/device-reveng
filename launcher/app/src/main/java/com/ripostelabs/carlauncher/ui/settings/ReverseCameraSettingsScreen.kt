package com.ripostelabs.carlauncher.ui.settings

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.McuFactorySet
import com.ripostelabs.carlauncher.carlib.ReverseSource
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.ReverseCameraDecoder
import com.ripostelabs.carlauncher.data.SettingKeys
import com.ripostelabs.carlauncher.ui.CameraTestActivity
import com.ripostelabs.carlauncher.ui.SurroundCameraActivity

/**
 * v1.3 — Reverse camera. Mirrors the vendor "Reversing/Backcar" settings page, reskinned.
 * Backed by SysVar (CAR_API §2.3). The reverse *view* itself is handled by the launcher's
 * ReverseOverlay + the gateway; this page only tunes the vendor's reverse behaviour.
 *
 * ⚠ Enum option values (window type, track-line type) are inferred from key naming; the video
 * input rows are the vendor picker's (BackcarSignalTypeSet.java:61-93, [ReverseCameraDecoder])
 * and the speed threshold 0/1/2 → 0/30/50 km/h is confirmed (EventService.java:9003-9010).
 */
@Composable
fun ReverseCameraSettingsScreen(
    controller: CarSettingsController,
    onBack: () -> Unit,
) {
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SettingsScaffold(title = "Reverse camera", onBack = onBack) {
        SettingsSection(title = "Audio while reversing") {
            // The stock three modes; the rows land in the 0F frame's Sys_McuSet bits.
            PickerSetting(
                label = "Reverse mute",
                current = McuFactorySet.ReverseMute.of(snap).ordinal,
                options = REVERSE_MUTE_OPTIONS,
                onSelect = { index ->
                    val mode = McuFactorySet.ReverseMute.values()[index]
                    mode.rows(snap).forEach { (key, value) -> controller.setString(key, value) }
                },
            )
        }

        SettingsSection(title = "Camera input") {
            // Stock's reverse type (getDataReverseType): the row reaches the MCU in the 0F frame
            // (bit 3). The launcher still opens the camera on reverse whatever is picked here.
            PickerSetting(
                label = "Reverse type",
                description = "Which reverse hardware is fitted, as stock tells the MCU",
                current = controller.getInt(McuFactorySet.KEY_BACKCAR_TYPE, BACKCAR_TYPE_HD_CAMERA),
                options = REVERSE_TYPE_OPTIONS,
                onSelect = { controller.setInt(McuFactorySet.KEY_BACKCAR_TYPE, it) },
            )
            PickerSetting(
                label = "Video input type",
                current = controller.getInt(SettingKeys.BACKCAR_VIDEO_TYPE, 0),
                options = ReverseCameraDecoder.VIDEO_TYPES,
                onSelect = { controller.setInt(SettingKeys.BACKCAR_VIDEO_TYPE, it) },
            )
            PickerSetting(
                label = "TW6752 decoder input",
                description = "Only used on units with the TW6752 video decoder",
                current = controller.getInt(SettingKeys.BACKCAR_6752_VIDEO_TYPE, 0),
                options = ReverseCameraDecoder.VIDEO_TYPES,
                onSelect = { controller.setInt(SettingKeys.BACKCAR_6752_VIDEO_TYPE, it) },
            )
            ToggleSetting(
                label = "Mirror image",
                description = "Flip the reverse image horizontally",
                checked = controller.getBoolean(SettingKeys.BACKCAR_CAMERA_MIRRORING, true),
                onChange = { controller.setBoolean(SettingKeys.BACKCAR_CAMERA_MIRRORING, it) },
            )
            // The picture without reversing: a bench check of the feed and the input type above.
            ActionRow(
                label = "Test camera",
                description = "Show the camera picture now, without reverse",
                onClick = { context.startActivity(Intent(context, CameraTestActivity::class.java)) },
            )
            // The surround decoder's four channels, beside the reverse check (os/CAMERA_360.md).
            ActionRow(
                label = "360 cameras",
                description = "Show the four surround cameras in a grid",
                onClick = { context.startActivity(Intent(context, SurroundCameraActivity::class.java)) },
            )
        }

        SettingsSection(title = "Display") {
            ToggleSetting(
                label = "Full screen",
                checked = controller.getBoolean(SettingKeys.BACKCAR_FULLSCREEN, false),
                onChange = { controller.setBoolean(SettingKeys.BACKCAR_FULLSCREEN, it) },
            )
            PickerSetting(
                label = "Window layout",
                current = controller.getInt(SettingKeys.BACKCAR_WINDOW_TYPE, 0),
                options = listOf(
                    0 to "Full",
                    1 to "Split (camera + radar)",
                ),
                onSelect = { controller.setInt(SettingKeys.BACKCAR_WINDOW_TYPE, it) },
            )
            ToggleSetting(
                label = "Show radar overlay",
                description = "Draw parking-sensor distances over the camera",
                checked = controller.getBoolean(SettingKeys.BACKCAR_DISPLAY_RADAR, true),
                onChange = { controller.setBoolean(SettingKeys.BACKCAR_DISPLAY_RADAR, it) },
            )
        }

        SettingsSection(title = "Guide lines") {
            ToggleSetting(
                label = "Static guide lines",
                checked = controller.getBoolean(SettingKeys.REVERSE_ASSIST_LINE, true),
                onChange = { controller.setBoolean(SettingKeys.REVERSE_ASSIST_LINE, it) },
            )
            PickerSetting(
                label = "Dynamic trajectory",
                description = "Steering-linked trajectory line",
                current = controller.getInt(SettingKeys.TRACK_LINE_TYPE, 0),
                options = listOf(
                    0 to "Off",
                    1 to "Static",
                    2 to "Dynamic (steering)",
                ),
                onSelect = { controller.setInt(SettingKeys.TRACK_LINE_TYPE, it) },
            )
        }

        SettingsSection(title = "Behaviour") {
            // Stock's "protocol reverse". The wire always counts; CAN can only add (ReverseSource).
            PickerSetting(
                label = "Reverse signal",
                description = "Use the CAN gear when the reverse wire is not connected",
                current = controller.getInt(SettingKeys.REVERSE_SOURCE, ReverseSource.DEFAULT.setting),
                options = listOf(
                    ReverseSource.WIRE.setting to "Reverse wire",
                    ReverseSource.WIRE_OR_CAN.setting to "Wire or CAN gear",
                ),
                onSelect = { controller.setInt(SettingKeys.REVERSE_SOURCE, it) },
            )
            PickerSetting(
                label = "Auto-exit speed",
                description = "Leave reverse view above this speed",
                current = controller.getInt(SettingKeys.BACKCAR_SPEED_THRESHOLD, 0),
                options = listOf(
                    0 to "Off",
                    1 to "30 km/h",
                    2 to "50 km/h",
                ),
                onSelect = { controller.setInt(SettingKeys.BACKCAR_SPEED_THRESHOLD, it) },
            )
        }
    }
}

/** `Sys_Backcar_type_key_set` values from stock's clickReverseType (ItemTextRightCheckBoxView:314-340). */
private const val BACKCAR_TYPE_HD_CAMERA = 1

private val REVERSE_TYPE_OPTIONS = listOf(
    BACKCAR_TYPE_HD_CAMERA to "Aftermarket camera",
    0 to "Factory camera",
    2 to "No camera",
    3 to "Factory radar",
)

/** Picker rows in [McuFactorySet.ReverseMute] order, the stock labels (getDataBackCarMute). */
private val REVERSE_MUTE_OPTIONS = listOf(
    McuFactorySet.ReverseMute.MUTE.ordinal to "Mute",
    McuFactorySet.ReverseMute.ATTENUATE.ordinal to "Lower",
    McuFactorySet.ReverseMute.OFF.ordinal to "Off",
)
