package com.reveng.carlauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.SettingKeys

/**
 * v1.3 — Reverse camera. Mirrors the vendor "Reversing/Backcar" settings page, reskinned.
 * Backed by SysVar (CAR_API §2.3). The reverse *view* itself is handled by the launcher's
 * ReverseOverlay + the gateway; this page only tunes the vendor's reverse behaviour.
 *
 * ⚠ Enum option values (video-input type, window type, track-line type) are inferred from key
 * naming; the speed threshold 0/1/2 → 0/30/50 km/h is confirmed (EventService.java:9003-9010).
 */
@Composable
fun ReverseCameraSettingsScreen(
    controller: CarSettingsController,
    onBack: () -> Unit,
) {
    val snap by controller.snapshot.collectAsStateWithLifecycle()
    snap

    SettingsScaffold(title = "Reverse camera", onBack = onBack) {
        SettingsSection(title = "Camera input") {
            PickerSetting(
                label = "Video input type",
                current = controller.getInt(SettingKeys.BACKCAR_VIDEO_TYPE, 0),
                options = listOf(
                    0 to "CVBS (analog)",
                    1 to "AHD 720p",
                    2 to "AHD 1080p",
                ),
                onSelect = { controller.setInt(SettingKeys.BACKCAR_VIDEO_TYPE, it) },
            )
            PickerSetting(
                label = "TW6752 decoder input",
                description = "Only used on units with the TW6752 video decoder",
                current = controller.getInt(SettingKeys.BACKCAR_6752_VIDEO_TYPE, 0),
                options = listOf(
                    0 to "CVBS",
                    1 to "AHD 720p",
                    2 to "AHD 1080p",
                ),
                onSelect = { controller.setInt(SettingKeys.BACKCAR_6752_VIDEO_TYPE, it) },
            )
            ToggleSetting(
                label = "Mirror image",
                description = "Flip the reverse image horizontally",
                checked = controller.getBoolean(SettingKeys.BACKCAR_CAMERA_MIRRORING, true),
                onChange = { controller.setBoolean(SettingKeys.BACKCAR_CAMERA_MIRRORING, it) },
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
