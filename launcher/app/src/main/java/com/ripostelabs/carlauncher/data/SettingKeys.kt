package com.ripostelabs.carlauncher.data

/**
 * v1.1 — the vendor SysVar keyname strings the settings suite reads/writes.
 *
 * These are the `keyname` values stored in the vendor provider
 * `content://com.szchoiceway.eventcenter.SysVarProvider/SysVar` (CAR_API §2.3), transcribed
 * from the decompiled `SysProviderOpt.java`. The category screens reference these constants so
 * the exact strings live in one place; the Advanced browser instead enumerates the live table,
 * so it surfaces keys not listed here too.
 *
 * Grouped by settings category. Values are TEXT in the provider (ints/bools are parsed).
 *
 * The vendor firmware declares **455** SysVar keys (SysProviderOpt.java lines 33–491). This is
 * the curated subset the friendly category screens use; the Advanced browser exposes the full
 * live table so nothing is hidden. Enum value→label tables are NOT recoverable from the
 * decompiled trees (the `com.szchoiceway.settings` APK that renders the vendor preference
 * screens isn't in the repo), so option mappings here are inferred and annotated per-screen.
 */
object SettingKeys {

    // ---- Display / Illumination -------------------------------------------
    const val LIGHT_LEVEL_SET = "Sys_Light_Level_set"
    const val BRIGHTNESS = "Set_Brightness_Key"
    const val CONTRAST = "Set_Contrast_Key"
    const val SET_DAY_LIGHT = "Set_Day_Light"
    const val SET_NIGHT_LIGHT = "Set_Night_Light"
    const val DAY_NIGHT_MODE = "Sys_Day_Night_Mode"
    const val MCU_PANEL_LIGHT = "Sys_MCU_Panel_Light_Key"
    const val MCU_SOFT_LIGHT_CONTROL = "Sys_Mcu_soft_light_control_Set"
    const val CAR_AMBIENT_LIGHT = "sys_car_ambient_light_key"
    const val MULTICOLOR_KEY_LIGHT = "sys_multicolor_key_light"

    // ---- Reverse camera ----------------------------------------------------
    const val BACKCAR_TYPE = "SYS_BACKCAR_TYPE"
    const val BACKCAR_VIDEO_TYPE = "Sys_backcar_Video_Type"
    const val BACKCAR_6752_VIDEO_TYPE = "Sys_6752_Backcar_Video_Type"
    const val BACKCAR_CAMERA_MIRRORING = "Sys_Backcar_Camera_Mirroring"
    const val BACKCAR_FULLSCREEN = "Sys_backcar_fullscreen"
    const val BACKCAR_WINDOW_TYPE = "Sys_Backcar_Window_Type"
    const val BACKCAR_SPEED_THRESHOLD = "Sys_Backcar_speed_threshold"
    const val BACKCAR_DISPLAY_RADAR = "Sys_BackCar_Display_Radar_Key"
    const val REVERSE_ASSIST_LINE = "Sys_Reverse_Assist_Line_Key"
    const val TRACK_LINE_TYPE = "Sys_TrackLineType"

    // ---- Parking radar -----------------------------------------------------
    const val RADAR_TYPE_ENABLE = "Sys_RadarTypeEnable"
    const val RADAR_TONE_ENABLE = "Sys_RadarToneEnable"
    const val RADAR_TONE_TYPE = "Sys_RadarToneType"

    // ---- Audio / EQ (SysVar side; AIDL handles live EQ) --------------------
    const val CAR_SPEED_UNIT = "Sys_Car_Speed_Unit" // 0=km/h 1=mph
    const val SHOW_CAR_SPEED = "Set_ShowCarSpeed"
    const val TOUCH_BEEP = "Set_TouchBeep"
    const val DSP_LOUDNESS = "Set_Dsp_Loud_On_Off_Key"
    const val REVERSING_ATTENUATION = "Sys_Reversing_Attenuation"

    // ---- OEM per-source volume gains (SysProviderOpt.java) ------------------
    // Each trims the gain of one audio source relative to the main volume.
    const val VOL_MUSIC = "Sys_Music_Volume_Gain"
    const val VOL_BT_MUSIC = "Sys_BT_Music_Volume_Gain"
    const val VOL_BT_CALL = "Sys_BT_Volume_Gain"
    const val VOL_RADIO = "Sys_Radio_Volume_Gain"
    const val VOL_USB = "Sys_Car_USB_Volume_Gain"
    const val VOL_AUX = "Sys_Aux_Volume_Gain"
    const val VOL_DVD = "Sys_Dvd_Volume_Gain"
    const val VOL_MOVIE = "Sys_Movie_Volume_Gain"
    const val VOL_TV = "Sys_TV_Volume_Gain"
    const val VOL_OTHER = "Sys_Other_Volume_Gain"
    const val VOL_NAV_MIX = "Sys_Igo_Mixing_Volume"
    const val ADJUST_OEM_VOLUME = "Sys_ajust_the_original_car_volume"

    // ---- Climate / AC ------------------------------------------------------
    const val AIR_PANEL_TYPE = "Sys_Air_Pannel_type"
    const val AIR_CONDITIONING_BAUD = "Sys_Air_conditioning_baud_rate"
    const val REAR_AIR = "Sys_rear_air"
    const val BAR_AIR_SHOW = "Sys_BarAirShow_Set"
    const val SEAT_HEAT = "Sys_air_seat_hot_key"
    const val SEAT_COOL = "Sys_air_seat_cold_key"
    const val WHEEL_HEAT = "Sys_air_wheel_hot_key"
    const val TEMP_UNIT = "Sys_Tmp_Unit_set" // inferred 0=°C 1=°F
    const val SHOW_TEMP = "Sys_Show_Temp_key_set"

    // ---- Radio -------------------------------------------------------------
    const val RDO_FAVORITE_PREFIX = "Rdo_MyFavorite" // Rdo_MyFavorite0..5

    // ---- Steering wheel ----------------------------------------------------
    const val WHEEL_KEY_LEARN_CUSTOM = "wheel_key_learn_custom"
    const val WHEEL_CUSTOM_KEY_SAVE = "Set_Mcu_Wheel_Custom_Key_Save"

    // ---- Power / ACC / Sleep ----------------------------------------------
    const val ACC_DELAY = "Sys_Acc_Delay"
    const val ACC_OFF_DELAY = "ACC_OFF_DELAY"
    const val ACC_ON_DELAY = "SET_ACC_ON_DELAY"
    const val SLEEP_SWITCH = "Sys_Sleep_Switch"
    const val SLEEP_TIME = "SYS_SLEEP_TIME"
    const val POWER_OFF_DELAY = "Sys_Power_Off_Delay"

    // ---- System / About (mostly read-only) --------------------------------
    const val CAR_TYPE = "Sys_CarType"
    const val VEHICLE_SERIES = "Sys_Vehicle_deries"
    const val CUSTOMER_TYPE = "Sys_CustomerType"
    const val MCU_VERSION = "Sys_McuVersion"
    const val CANBOX_VERSION = "Sys_Upgrade_Canbox_Version"
    const val CAN_BAUD_RATE = "Sys_Can_baud_rate"
    const val MCU_COM_BAUDRATE = "Sys_MCUComBaudRate"
    const val SCREEN_WIDTH = "Sys_Screen_Width"
    const val SCREEN_HEIGHT = "Sys_Screen_Height"
    const val SCREEN_DENSITY = "Sys_Screen_Density"
    const val UI_NUMBER_KEY = "uiNumberKey"
    const val LANGUAGE = "Set_Language_Select"
    const val TIME_FORMAT = "Sys_Time_12_24_Format" // inferred 0=24h 1=12h
    const val APP_VERSION = "Sys_AppVersion"
    const val SYSTEM_VERSION = "Sys_version"
}
