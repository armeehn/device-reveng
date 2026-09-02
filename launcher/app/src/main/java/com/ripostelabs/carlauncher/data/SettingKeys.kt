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
 * live table so nothing is hidden. Value domains marked below come from the decompiled
 * `com.szchoiceway.settings` app (`ItemTextRightCheckBoxView.java`, `FactorySetFragment.java`)
 * and the gateway (`EventService.java`); anything unmarked is still inferred.
 */
object SettingKeys {

    // ---- Display / Illumination -------------------------------------------
    /** 0..3 dim level forwarded to SystemUI (`EventService.updateLightLevel`), NOT the backlight. */
    const val LIGHT_LEVEL_SET = "Sys_Light_Level_set"
    const val BRIGHTNESS = "Set_Brightness_Key"
    const val CONTRAST = "Set_Contrast_Key"
    /** MCU backlight 0..20 (defaults 18 / 8); reaches the MCU only via `sendBacklight`. */
    const val SET_DAY_LIGHT = "Set_Day_Light"
    const val SET_NIGHT_LIGHT = "Set_Night_Light"
    /** 0 = follow headlamps, 1 day, 2 night, 3 by sunrise/sunset (default 3). */
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

    // ---- Power / ACC / Sleep (domains in PowerOptions) ---------------------
    /** Seconds; the gateway sends it to the MCU as mm:ss (`sendAccDelayTime`). */
    const val ACC_DELAY = "Sys_Acc_Delay"
    /** Never read by the gateway (a change only re-sends the factory MCU set); not exposed. */
    const val ACC_OFF_DELAY = "ACC_OFF_DELAY"
    /** Seconds 0..7, packed `& 7` into MCU frame 0x10. */
    const val ACC_ON_DELAY = "SET_ACC_ON_DELAY"
    /** 0/1 factory flag, bit4 of factory MCU byte 10. */
    const val SLEEP_SWITCH = "Sys_Sleep_Switch"
    /** Enum 1/2/3 (default 2) -> MCU 960/1440/2880; unit UNVERIFIED. */
    const val SLEEP_TIME = "SYS_SLEEP_TIME"
    /** 0/1 factory flag "ACC off delay", bit1 of factory MCU byte 8. */
    const val POWER_OFF_DELAY = "Sys_Power_Off_Delay"
    /** 0/1, default 1: blank the screen when ACC changes. */
    const val SCREEN_OFF_WHEN_ACC_CHANGE = "Sys_Screen_Off_When_Acc_Change"
    /** Seconds in {0, 60, 300, 600, 1800}; 0 = never. */
    const val AUTO_SCREENSAVER_TIME = "SYS_AUTO_START_SCREENSAVER_TIME"
    /** Same domain as the screensaver; the gateway acts on it only for customer type 69. */
    const val AUTO_CLOSE_SCREEN_TIME = "SYS_AUTO_START_CLOSE_SCREEN_TIME"

    // ---- Vendor chrome (nav bar geometry, read by the gateway at boot and on change) -----
    /** Bottom bar height in px; 0 = no bar. Factory options 170/212/220/270. Needs LANDSCAPE=1. */
    const val NAVIBAR_HEIGHT = "Sys_Customer_NaviBar_Height_Key"
    /** 1 = landscape layout branch in `SystemUtils.initNaviAndStatusBarHeight`. */
    const val LANDSCAPE = "Sys_Landscape"

    // ---- System / About (mostly read-only) --------------------------------
    /** Model index within [VEHICLE_SERIES] (Toyota: Camry 1, RAV4 2, Corolla 5, Highlander 7, C-HR 10). */
    const val CAR_TYPE = "Sys_CarType"
    /** Make: 0 none, 1 Toyota, 2 Ford, 7 Honda, 8 VW (`CanConstantInfo.VEHICLE_DERIES_*`). */
    const val VEHICLE_SERIES = "Sys_Vehicle_deries"
    /** Year/trim index inside the model (`YearType.TOYOTA_RAV4_TYPE_*`). */
    const val CAR_INFO_ID = "Sys_CarInfor_ID"
    /** CAN box vendor 1..18 (4 Raise, 6 Hiworld). */
    const val CAN_SUPPLIER_ID = "Sys_camry_air_Supplier_id"
    /**
     * OEM/customer id, default 88. Known meanings: 53 = OEM build with the gateway's own
     * status bar and USB multi-camera, 58 = original-car amplifier key relay, 13 = CHWY
     * instrument-panel animation (`EventService.java`, `Customer.java`).
     */
    const val CUSTOMER_TYPE = "Sys_CustomerType"
    const val MCU_VERSION = "Sys_McuVersion"
    const val CANBOX_VERSION = "Sys_Upgrade_Canbox_Version"
    const val CAN_BAUD_RATE = "Sys_Can_baud_rate"
    const val MCU_COM_BAUDRATE = "Sys_MCUComBaudRate"
    const val SCREEN_WIDTH = "Sys_Screen_Width"
    const val SCREEN_HEIGHT = "Sys_Screen_Height"
    const val SCREEN_DENSITY = "Sys_Screen_Density"
    /** Skin id (`SysProviderOpt.SYS_UI_NUMBER_KEY`); 0 = common. Was wrongly "uiNumberKey". */
    const val UI_NUMBER_KEY = "Sys_UINumber"
    // Setup Doctor diagnostics, read-only (decompiled eventcenter SysProviderOpt.java:335/426/458).
    /** "1" (the default) = `kill3rdAPK` is a no-op on `sendMode` (EventService.java:6581/8294). */
    const val SOUND_MANAGER_TYPE = "Sys_SoundManager_Type"
    const val LANGUAGE = "Set_Language_Select"
    const val TIME_FORMAT = "Sys_Time_12_24_Format" // inferred 0=24h 1=12h
    const val APP_VERSION = "Sys_AppVersion"
    const val SYSTEM_VERSION = "Sys_version"
}
